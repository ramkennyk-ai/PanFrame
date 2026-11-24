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
import java.io.File

@Composable
fun RePanScreen(
    sidecar: PanSessionSidecar,
    onSaveAndExport: (PanSessionSidecar) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val animX = remember { Animatable(0.5f) }
    val animY = remember { Animatable(0.5f) }
    val recorded = remember { mutableStateListOf<BoxKeyframe>() }
    var isReRecording by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(sidecar.rawVideoPath))))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(isReRecording) {
        if (isReRecording) {
            recorded.clear()
            exoPlayer.seekTo(0)
            exoPlayer.play()
            while (isActive && isReRecording) {
                val posUs = exoPlayer.currentPosition * 1000L
                recorded.add(BoxKeyframe(posUs, animX.value, animY.value))
                if (exoPlayer.playbackState == ExoPlayer.STATE_ENDED) {
                    isReRecording = false
                }
                delay(33L)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(CinemaDark)) {
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

        Surface(
            color = CinemaSurface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 42.dp)
        ) {
            Text(
                text = if (isReRecording) "● RECORDING NEW PAN PATH..." else "RE-PAN STUDIO • TAP TO GLIDE",
                color = if (isReRecording) CinemaAmber else Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (sidecar.isLandscapeCapture) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { scope.launch { animX.animateTo(0.22f, tween(420)) } }) { Text("◀ Speaker A") }
                    FilledTonalButton(onClick = { scope.launch { animX.animateTo(0.50f, tween(420)) } }) { Text("Center") }
                    FilledTonalButton(onClick = { scope.launch { animX.animateTo(0.78f, tween(420)) } }) { Text("Speaker B ▶") }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel) { Text("Keep Original") }
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = CinemaAmber),
                    onClick = {
                        if (!isReRecording) {
                            isReRecording = true
                        } else {
                            isReRecording = false
                            val finalFrames = if (recorded.size > 2) recorded.toList() else sidecar.keyframes
                            onSaveAndExport(sidecar.copy(keyframes = finalFrames))
                        }
                    }
                ) {
                    Text(if (!isReRecording) "Start Re-Pan Pass" else "Save & Export HD", color = Color.Black)
                }
            }
        }
    }
}
