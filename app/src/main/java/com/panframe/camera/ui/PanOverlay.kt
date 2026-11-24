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
                    val pos = (offset.y / size.height).coerceIn(halfSpan, 1f - halfSpan)
                    if (isLandscapeMode) onTargetSelected(pos, 0.5f, true)
                    else onTargetSelected(0.5f, pos, true)
                }
            }
            .pointerInput(isLandscapeMode) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val (cx, cy) = centerProvider()
                    val cur = if (isLandscapeMode) cx else cy
                    val next = (cur + dragAmount.y / size.height).coerceIn(halfSpan, 1f - halfSpan)
                    if (isLandscapeMode) onTargetSelected(next, 0.5f, false)
                    else onTargetSelected(0.5f, next, false)
                }
            }
    ) {
        val (cx, cy) = centerProvider()
        val activeNorm = if (isLandscapeMode) cx else cy
        val boxW = size.width
        val boxH = size.height * ratio
        val top = (activeNorm * size.height - boxH / 2f).coerceIn(0f, size.height - boxH)

        val scrim = Color(0x88000000)
        drawRect(scrim, Offset.Zero, Size(size.width, top))
        drawRect(scrim, Offset(0f, top + boxH), Size(size.width, size.height - (top + boxH)))

        drawRoundRect(
            color = CinemaAmber,
            topLeft = Offset(4f, top + 3f),
            size = Size(boxW - 8f, boxH - 6f),
            cornerRadius = CornerRadius(18f, 18f),
            style = Stroke(width = 5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 14f), 0f))
        )
        drawCircle(CinemaAmber.copy(alpha = 0.85f), 7f, Offset(boxW / 2f, top + boxH / 2f))
    }
}
