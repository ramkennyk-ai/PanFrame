package com.panframe.camera.export

import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import com.panframe.camera.model.BoxKeyframe
import com.panframe.camera.model.PanSmoother

@OptIn(UnstableApi::class)
object LivePanEffects {
    private const val RATIO = 81f / 256f
    private const val HALF = RATIO / 2f

    /** true = export the crop at native size (~1214x2160 / 2160x1214 from 4K): slower, bigger files. */
    private val NATIVE_CROP_OUTPUT = false

    data class OutSize(val w: Int, val h: Int)

    /** srcW/srcH are the UPRIGHT source dimensions. */
    fun outputSize(isLandscapeCapture: Boolean, srcW: Int, srcH: Int): OutSize {
        if (NATIVE_CROP_OUTPUT) {
            return if (isLandscapeCapture) OutSize(even(srcH * 9 / 16), even(srcH))
            else OutSize(even(srcW), even(srcW * 9 / 16))
        }
        val shortSide = if (maxOf(srcW, srcH) >= 3000) 1080 else 720
        val longSide = shortSide * 16 / 9
        return if (isLandscapeCapture) OutSize(shortSide, longSide) else OutSize(longSide, shortSide)
    }

    fun bitrateFor(out: OutSize): Int =
        (out.w.toLong() * out.h * 7.5).toLong().coerceIn(4_000_000L, 60_000_000L).toInt()

    private fun even(v: Int) = v and 1.inv()

    fun buildEffects(isLandscapeCapture: Boolean, out: OutSize, keyframes: List<BoxKeyframe>): List<Effect> {
        val panTransform = object : MatrixTransformation {
            override fun getMatrix(presentationTimeUs: Long): Matrix {
                val (rawX, rawY) = PanSmoother.sampleAt(keyframes, presentationTimeUs)
                return Matrix().apply {
                    if (isLandscapeCapture) {
                        val cx = rawX.coerceIn(HALF, 1f - HALF)
                        postTranslate((0.5f - cx) * 2f, 0f)
                    } else {
                        val cy = rawY.coerceIn(HALF, 1f - HALF)
                        postTranslate(0f, (cy - 0.5f) * 2f)
                    }
                }
            }
        }
        return listOf(
            panTransform,
            Presentation.createForWidthAndHeight(out.w, out.h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        )
    }
}
