package com.panframe.camera.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class BoxKeyframe(
    val timeUs: Long,
    val centerX: Float,
    val centerY: Float,
    val zoom: Float = 1.0f
)

@Serializable
data class PanSessionSidecar(
    val rawVideoPath: String,
    val isLandscapeCapture: Boolean,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val keyframes: List<BoxKeyframe>
)

object PanSmoother {
    fun smoothKeyframes(
        raw: List<BoxKeyframe>,
        deadzone: Float = 0.025f,
        alpha: Float = 0.18f
    ): List<BoxKeyframe> {
        if (raw.size <= 2) return raw

        val stabilized = ArrayList<BoxKeyframe>(raw.size)
        var anchorX = raw.first().centerX
        var anchorY = raw.first().centerY
        for (kf in raw) {
            if (abs(kf.centerX - anchorX) > deadzone) anchorX = kf.centerX
            if (abs(kf.centerY - anchorY) > deadzone) anchorY = kf.centerY
            stabilized.add(kf.copy(centerX = anchorX, centerY = anchorY))
        }

        val forward = ArrayList<BoxKeyframe>(stabilized.size)
        var fx = stabilized.first().centerX
        var fy = stabilized.first().centerY
        for (kf in stabilized) {
            fx += alpha * (kf.centerX - fx)
            fy += alpha * (kf.centerY - fy)
            forward.add(kf.copy(centerX = fx, centerY = fy))
        }

        val result = MutableList(forward.size) { forward[it] }
        var bx = forward.last().centerX
        var by = forward.last().centerY
        for (i in forward.indices.reversed()) {
            bx += alpha * (forward[i].centerX - bx)
            by += alpha * (forward[i].centerY - by)
            result[i] = forward[i].copy(
                centerX = bx.coerceIn(0f, 1f),
                centerY = by.coerceIn(0f, 1f)
            )
        }
        return result
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
        val x = prev.centerX + (next.centerX - prev.centerX) * t
        val y = prev.centerY + (next.centerY - prev.centerY) * t
        return x to y
    }
}
