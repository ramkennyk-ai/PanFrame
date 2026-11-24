package com.panframe.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.panframe.camera.ui.theme.CinemaAmber

private const val BOX_RATIO = 81f / 256f

/**
 * Must be placed in a container that has EXACTLY the video frame's aspect ratio.
 * Portrait capture: horizontal 16:9 band (drag Y). Landscape capture: vertical 9:16 band (drag X).
 */
@Composable
fun PanDottedOverlay(
    isLandscapeMode: Boolean,
    centerProvider: () -> Pair<Float, Float>,
    onTargetSelected: (Float, Float, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val halfSpan = BOX_RATIO / 2f

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(isLandscapeMode) {
                detectTapGestures { offset ->
                    if (isLandscapeMode) {
                        val p = (offset.x / size.width).coerceIn(halfSpan, 1f - halfSpan)
                        onTargetSelected(p, 0.5f, true)
                    } else {
                        val p = (offset.y / size.height).coerceIn(halfSpan, 1f - halfSpan)
                        onTargetSelected(0.5f, p, true)
                    }
                }
            }
            .pointerInput(isLandscapeMode) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val (cx, cy) = centerProvider()
                    if (isLandscapeMode) {
                        val next = (cx + dragAmount.x / size.width).coerceIn(halfSpan, 1f - halfSpan)
                        onTargetSelected(next, 0.5f, false)
                    } else {
                        val next = (cy + dragAmount.y / size.height).coerceIn(halfSpan, 1f - halfSpan)
                        onTargetSelected(0.5f, next, false)
                    }
                }
            }
    ) {
        val (cx, cy) = centerProvider()
        val scrim = Color(0x88000000)
        val dash = Stroke(width = 5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 14f), 0f))

        if (isLandscapeMode) {
            val boxW = size.width * BOX_RATIO
            val left = (cx * size.width - boxW / 2f).coerceIn(0f, size.width - boxW)
            drawRect(scrim, Offset.Zero, Size(left, size.height))
            drawRect(scrim, Offset(left + boxW, 0f), Size(size.width - (left + boxW), size.height))
            drawRoundRect(
                color = CinemaAmber,
                topLeft = Offset(left + 3f, 4f),
                size = Size(boxW - 6f, size.height - 8f),
                cornerRadius = CornerRadius(18f, 18f),
                style = dash
            )
            drawCircle(CinemaAmber.copy(alpha = 0.85f), 7f, Offset(left + boxW / 2f, size.height / 2f))
        } else {
            val boxH = size.height * BOX_RATIO
            val top = (cy * size.height - boxH / 2f).coerceIn(0f, size.height - boxH)
            drawRect(scrim, Offset.Zero, Size(size.width, top))
            drawRect(scrim, Offset(0f, top + boxH), Size(size.width, size.height - (top + boxH)))
            drawRoundRect(
                color = CinemaAmber,
                topLeft = Offset(4f, top + 3f),
                size = Size(size.width - 8f, boxH - 6f),
                cornerRadius = CornerRadius(18f, 18f),
                style = dash
            )
            drawCircle(CinemaAmber.copy(alpha = 0.85f), 7f, Offset(size.width / 2f, top + boxH / 2f))
        }
    }
}
