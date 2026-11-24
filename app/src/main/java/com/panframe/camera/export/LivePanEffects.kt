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

    fun buildEffects(isLandscapeCapture: Boolean, keyframes: List<BoxKeyframe>): List<Effect> {
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
        val layout = if (isLandscapeCapture) {
            Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        } else {
            Presentation.createForWidthAndHeight(1920, 1080, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        }
        return listOf(panTransform, layout)
    }
}
