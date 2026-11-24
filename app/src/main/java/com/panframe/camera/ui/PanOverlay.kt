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

@Composable
fun PanDottedOverlay(
    isLandscapeMode: Boolean,
    centerProvider: () -> Pair<Float, Float>,
    onTargetSelected: (Float, Float, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val ratio = 81f / 256f
    val halfSpan = ratio / 2f

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(isLandscapeMode) {
                detectTapGestures { offset ->
                    val nx = (offset.x / size.width).coerceIn(halfSpan, 1f - halfSpan)
                    val ny = (offset.y / size.height).coerceIn(halfSpan, 1f - halfSpan)
                    onTargetSelected(nx, ny, true)
                }
            }
            .pointerInput(isLandscapeMode) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val (cx, cy) = centerProvider()
                    val nx = (cx + dragAmount.x / size.width).coerceIn(halfSpan, 1f - halfSpan)
                    val ny = (cy + dragAmount.y / size.height).coerceIn(halfSpan, 1f - halfSpan)
                    onTargetSelected(nx, ny, false)
                }
            }
    ) {
        val (cx, cy) = centerProvider()
        val boxW = if (isLandscapeMode) size.width * ratio else size.width
        val boxH = if (isLandscapeMode) size.height else size.height * ratio
        val left = if (isLandscapeMode) (cx * size.width - boxW / 2f).coerceIn(0f, size.width - boxW) else 0f
        val top = if (isLandscapeMode) 0f else (cy * size.height - boxH / 2f).coerceIn(0f, size.height - boxH)

        // Dimmed outer letterbox masks
        val scrim = Color(0x88000000)
        if (isLandscapeMode) {
            drawRect(scrim, Offset.Zero, Size(left, size.height))
            drawRect(scrim, Offset(left + boxW, 0f), Size(size.width - (left + boxW), size.height))
        } else {
            drawRect(scrim, Offset.Zero, Size(size.width, top))
            drawRect(scrim, Offset(0f, top + boxH), Size(size.width, size.height - (top + boxH)))
        }

        // Glowing dashed cinema crop box
        drawRoundRect(
            color = CinemaAmber,
            topLeft = Offset(left + 3f, top + 3f),
            size = Size(boxW - 6f, boxH - 6f),
            cornerRadius = CornerRadius(18f, 18f),
            style = Stroke(
                width = 5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 14f), 0f)
            )
        )

        // Center crosshair dot
        drawCircle(
            color = CinemaAmber.copy(alpha = 0.85f),
            radius = 7f,
            center = Offset(left + boxW / 2f, top + boxH / 2f)
        )
    }
}
