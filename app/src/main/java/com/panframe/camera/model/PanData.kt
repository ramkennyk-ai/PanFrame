package com.panframe.camera.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.exp

/** Box center in UPRIGHT video space, 0..1 (what you see on screen == what Media3 sees). */
@Serializable
data class BoxKeyframe(
    val timeUs: Long,
    val centerX: Float,
    val centerY: Float,
    val zoom: Float = 1.0f
)

@Serializable
data class PanSessionSidecar(
    val videoUri: String,          // MediaStore content:// uri of the original recording
    val baseName: String,          // pairs the original + cropped files
    val isLandscapeCapture: Boolean,
    val keyframes: List<BoxKeyframe>,
    val revision: Int = 0,         // bumped on every re-pan
    val exported: Boolean = false,
    val failed: Boolean = false
)

object PanJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(s: PanSessionSidecar): String =
        json.encodeToString(PanSessionSidecar.serializer(), s)
    fun decode(text: String): PanSessionSidecar? =
        runCatching { json.decodeFromString(PanSessionSidecar.serializer(), text) }.getOrNull()
}

object PanSmoother {
    /** Deadzone -> forward EMA -> backward EMA (zero phase, no lag). Time-based, so
     *  irregular sample rates (recording events vs 33 ms re-pan loop) behave the same. */
    fun smoothKeyframes(
        raw: List<BoxKeyframe>,
        deadzone: Float = 0.02f,
        tauSec: Float = 0.18f
    ): List<BoxKeyframe> {
        if (raw.size <= 2) return raw
        val kf = raw.sortedBy { it.timeUs }
        val n = kf.size

        val ix = FloatArray(n)
        val iy = FloatArray(n)
        var ax = kf[0].centerX
        var ay = kf[0].centerY
        for (i in 0 until n) {
            if (abs(kf[i].centerX - ax) > deadzone) ax = kf[i].centerX
            if (abs(kf[i].centerY - ay) > deadzone) ay = kf[i].centerY
            ix[i] = ax
            iy[i] = ay
        }

        val fx = FloatArray(n)
        val fy = FloatArray(n)
        fx[0] = ix[0]
        fy[0] = iy[0]
        for (i in 1 until n) {
            val a = alpha(kf[i].timeUs - kf[i - 1].timeUs, tauSec)
            fx[i] = fx[i - 1] + a * (ix[i] - fx[i - 1])
            fy[i] = fy[i - 1] + a * (iy[i] - fy[i - 1])
        }

        val bx = FloatArray(n)
        val by = FloatArray(n)
        bx[n - 1] = fx[n - 1]
        by[n - 1] = fy[n - 1]
        for (i in n - 2 downTo 0) {
            val a = alpha(kf[i + 1].timeUs - kf[i].timeUs, tauSec)
            bx[i] = bx[i + 1] + a * (fx[i] - bx[i + 1])
            by[i] = by[i + 1] + a * (fy[i] - by[i + 1])
        }

        return List(n) {
            kf[it].copy(centerX = bx[it].coerceIn(0f, 1f), centerY = by[it].coerceIn(0f, 1f))
        }
    }

    private fun alpha(dtUs: Long, tauSec: Float): Float {
        val dt = dtUs.coerceAtLeast(0L) / 1_000_000f
        return 1f - exp(-dt / tauSec)
    }

    fun sampleAt(keyframes: List<BoxKeyframe>, timeUs: Long): Pair<Float, Float> {
        if (keyframes.isEmpty()) return 0.5f to 0.5f
        if (timeUs <= keyframes.first().timeUs) return keyframes.first().centerX to keyframes.first().centerY
        if (timeUs >= keyframes.last().timeUs) return keyframes.last().centerX to keyframes.last().centerY

        var low = 0
        var high = keyframes.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midTime = keyframes[mid].timeUs
            when {
                midTime < timeUs -> low = mid + 1
                midTime > timeUs -> high = mid - 1
                else -> return keyframes[mid].centerX to keyframes[mid].centerY
            }
        }
        val prev = keyframes[high.coerceAtLeast(0)]
        val next = keyframes[low.coerceAtMost(keyframes.lastIndex)]
        val span = (next.timeUs - prev.timeUs).coerceAtLeast(1L)
        val t = ((timeUs - prev.timeUs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        return (prev.centerX + (next.centerX - prev.centerX) * t) to
            (prev.centerY + (next.centerY - prev.centerY) * t)
    }
}
