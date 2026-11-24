package com.panframe.camera.ui

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.panframe.camera.model.BoxKeyframe
import com.panframe.camera.model.PanSessionSidecar
import com.panframe.camera.ui.theme.CinemaAmber
import com.panframe.camera.ui.theme.CinemaDark
import com.panframe.camera.ui.theme.CinemaSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class RePanPhase { IDLE, RECORDING, REVIEW }

@Composable
fun RePanScreen(
    sidecar: PanSessionSidecar,
    onSaveAndExport: (PanSessionSidecar) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = sidecar.keyframes.firstOrNull()
    val animX = remember { Animatable(first?.centerX ?: 0.5f) }
    val animY = remember { Animatable(first?.centerY ?: 0.5f) }
    val recorded = remember { mutableStateListOf<BoxKeyframe>() }
    var phase by remember { mutableStateOf(RePanPhase.IDLE) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(sidecar.videoUri)))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    LaunchedEffect(phase) {
        if (phase == RePanPhase.RECORDING) {
            recorded.clear()
            exoPlayer.seekTo(0)
            exoPlayer.play()
            while (isActive && phase == RePanPhase.RECORDING) {
                recorded.add(BoxKeyframe(exoPlayer.currentPosition * 1000L, animX.value, animY.value))
                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                    exoPlayer.pause()
                    phase = RePanPhase.REVIEW
                }
                delay(33L)
            }
        }
    }

    val aspect = if (sidecar.isLandscapeCapture) 16f / 9f else 9f / 16f

    Box(
        modifier = Modifier.fillMaxSize().background(CinemaDark),
        contentAlignment = Alignment.Center
    ) {
        // Player and overlay share ONE rect with the video's aspect ratio.
        Box(modifier = Modifier.aspectRatio(aspect)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            PanDottedOverlay(
                isLandscapeMode = sidecar.isLandscapeCapture,
                centerProvider = { animX.value to animY.value },
                onTargetSelected = { nx, ny, smooth ->
                    scope.launch {
                        if (smooth) {
                            launch { animX.animateTo(nx, tween(380)) }
                            launch { animY.animateTo(ny, tween(380)) }
                        } else {
                            animX.snapTo(nx)
                            animY.snapTo(ny)
                        }
                    }
                }
            )
        }

        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Surface(
                color = CinemaSurface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
            ) {
                Text(
                    text = when (phase) {
                        RePanPhase.IDLE -> "RE-PAN STUDIO • press Start, then drag"
                        RePanPhase.RECORDING -> "● RECORDING NEW PAN PATH..."
                        RePanPhase.REVIEW -> "PAN CAPTURED • save or redo"
                    },
                    color = if (phase == RePanPhase.RECORDING) CinemaAmber else Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (phase) {
                    RePanPhase.IDLE -> {
                        OutlinedButton(onClick = onCancel) { Text("Keep Original") }
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = CinemaAmber),
                            onClick = { phase = RePanPhase.RECORDING }
                        ) { Text("Start Re-Pan Pass", color = Color.Black) }
                    }
                    RePanPhase.RECORDING -> {
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = CinemaAmber),
                            onClick = {
                                exoPlayer.pause()
                                phase = RePanPhase.REVIEW
                            }
                        ) { Text("Stop", color = Color.Black) }
                    }
                    RePanPhase.REVIEW -> {
                        OutlinedButton(onClick = {
                            exoPlayer.seekTo(0)
                            phase = RePanPhase.IDLE
                        }) { Text("Redo") }
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = CinemaAmber),
                            onClick = {
                                if (recorded.size > 2) {
                                    onSaveAndExport(
                                        sidecar.copy(
                                            keyframes = recorded.toList(),
                                            revision = sidecar.revision + 1,
                                            exported = false,
                                            failed = false
                                        )
                                    )
                                } else onCancel()
                            }
                        ) { Text("Save & Export", color = Color.Black) }
                    }
                }
            }
        }
    }
}
