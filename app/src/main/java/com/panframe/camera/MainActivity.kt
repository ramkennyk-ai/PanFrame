package com.panframe.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import com.panframe.camera.export.PanExportService
import com.panframe.camera.model.BoxKeyframe
import com.panframe.camera.model.PanJson
import com.panframe.camera.model.PanSessionSidecar
import com.panframe.camera.ui.PanDottedOverlay
import com.panframe.camera.ui.RePanScreen
import com.panframe.camera.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import android.view.Surface as ViewSurface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PanFrameTheme {
                PanFrameApp()
            }
        }
    }
}

/** Media-time clock: anchored to CameraX's recorded duration, interpolated by wall clock in between. */
private class RecClock {
    @Volatile private var anchorUs = 0L
    @Volatile private var anchorWallUs = 0L
    private var lastUs = 0L
    private fun wallUs() = SystemClock.elapsedRealtimeNanos() / 1000L
    fun reset() { lastUs = 0L }
    fun anchor(mediaUs: Long) { anchorUs = mediaUs; anchorWallUs = wallUs() }
    fun nowUs(): Long {
        val v = anchorUs + (wallUs() - anchorWallUs)
        lastUs = maxOf(lastUs, v)
        return lastUs
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@OptIn(ExperimentalCamera2Interop::class)
@SuppressLint("MissingPermission")
@Composable
fun PanFrameApp() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val exportStatus by PanExportService.status.collectAsState()
    // Activity rotates with the phone, so UI + preview + overlay are always upright.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
        hasPermissions = map[Manifest.permission.CAMERA] == true && map[Manifest.permission.RECORD_AUDIO] == true
    }
    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            permLauncher.launch(perms.toTypedArray())
        }
    }
    // Pick up exports interrupted by process death / reboot.
    LaunchedEffect(Unit) { PanExportService.resumePending(context) }

    var isRecording by remember { mutableStateOf(false) }
    var lockedLandscape by remember { mutableStateOf(false) }
    var recSeconds by remember { mutableStateOf(0L) }
    var is4KSupported by remember { mutableStateOf(false) }
    var minZoom by remember { mutableStateOf(1.0f) }
    var currentZoom by remember { mutableStateOf(1.0f) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var lastSidecar by remember { mutableStateOf<Pair<File, PanSessionSidecar>?>(null) }
    var showRePan by remember { mutableStateOf(false) }

    val animX = remember { Animatable(0.5f) }
    val animY = remember { Animatable(0.5f) }
    val keyframes = remember { mutableStateListOf<BoxKeyframe>() }
    val recClock = remember { RecClock() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    // (Re)bind camera whenever the phone changes between portrait and landscape.
    // Also keyed on showRePan: the camera is released while the Re-Pan player is on screen.
    LaunchedEffect(hasPermissions, isLandscape, showRePan) {
        if (!hasPermissions) return@LaunchedEffect
        val provider = ProcessCameraProvider.getInstance(context).await()
        if (showRePan) {
            provider.unbindAll()
            activeCamera = null
            videoCapture = null
            return@LaunchedEffect
        }
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val camInfo = selector.filter(provider.availableCameraInfos).firstOrNull()
        is4KSupported = camInfo?.let {
            Recorder.getVideoCapabilities(it).getSupportedQualities(DynamicRange.SDR).contains(Quality.UHD)
        } == true

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.UHD, Quality.FHD, Quality.HD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .build()
        val vidCapBuilder = VideoCapture.Builder(recorder)

        // Only request a locked 30 fps if the device actually advertises it.
        val fps30 = camInfo?.let {
            Camera2CameraInfo.from(it)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }?.firstOrNull { it.lower == 30 && it.upper == 30 }
        if (fps30 != null) {
            Camera2Interop.Extender(vidCapBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps30)
        }
        val vidCap = vidCapBuilder.build()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        @Suppress("DEPRECATION")
        val rotation = previewView.display?.rotation
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val viewPort = ViewPort.Builder(if (isLandscape) Rational(16, 9) else Rational(9, 16), rotation).build()

        provider.unbindAll()
        val cam = runCatching {
            provider.bindToLifecycle(
                lifecycleOwner, selector,
                UseCaseGroup.Builder().addUseCase(preview).addUseCase(vidCap).setViewPort(viewPort).build()
            )
        }.getOrElse {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, vidCap)
        }
        activeCamera = cam
        videoCapture = vidCap
        minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1.0f
        currentZoom = 1.0f
    }

    // Box path sampled every 33 ms while recording (independent of how often CameraX emits Status events).
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isActive) {
                keyframes.add(BoxKeyframe(recClock.nowUs(), animX.value, animY.value))
                delay(33L)
            }
        }
    }

    // Re-center the box when the phone flips between portrait and landscape.
    LaunchedEffect(isLandscape) {
        if (!isRecording) {
            animX.snapTo(0.5f)
            animY.snapTo(0.5f)
        }
    }

    val effectiveLandscape = if (isRecording) lockedLandscape else isLandscape

    val currentSidecar = lastSidecar
    if (showRePan && currentSidecar != null) {
        val (jsonFile, sidecarObj) = currentSidecar
        RePanScreen(
            sidecar = sidecarObj,
            onSaveAndExport = { updated ->
                jsonFile.writeText(PanJson.encode(updated))
                lastSidecar = jsonFile to updated
                showRePan = false
                PanExportService.enqueue(context, jsonFile.absolutePath, force = true)
            },
            onCancel = { showRePan = false }   // original is already queued/exported; don't enqueue again
        )
        return
    }

    fun startRecording() {
        val vc = videoCapture ?: return
        PanExportService.setCameraBusy(context, true)
        lockedLandscape = isLandscape
        vc.targetRotation = previewView.display?.rotation ?: ViewSurface.ROTATION_0
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        keyframes.clear()
        recSeconds = 0L

        val base = "PanFrame_${System.currentTimeMillis()}"
        val tag = if (lockedLandscape) "16x9_Original" else "9x16_Original"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${base}_$tag")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PanFrame")
        }
        val outputOpts = MediaStoreOutputOptions.Builder(
            context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        activeRecording = vc.output
            .prepareRecording(context, outputOpts)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recClock.reset()
                        recClock.anchor(0L)
                        isRecording = true
                    }
                    is VideoRecordEvent.Status -> {
                        val us = event.recordingStats.recordedDurationNanos / 1000L
                        recSeconds = us / 1_000_000L
                        recClock.anchor(us)
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        activeRecording = null
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                        if (!event.hasError()) {
                            val sidecar = PanSessionSidecar(
                                videoUri = event.outputResults.outputUri.toString(),
                                baseName = base,
                                isLandscapeCapture = lockedLandscape,
                                keyframes = keyframes.toList()
                            )
                            val jsonFile = File(PanExportService.sidecarDir(context), "$base.json")
                            jsonFile.writeText(PanJson.encode(sidecar))
                            lastSidecar = jsonFile to sidecar
                            PanExportService.setCameraBusy(context, false)
                            PanExportService.enqueue(context, jsonFile.absolutePath)
                        } else {
                            PanExportService.setCameraBusy(context, false)
                        }
                    }
                }
            }
    }

    val frameAspect = if (effectiveLandscape) 16f / 9f else 9f / 16f

    Box(
        modifier = Modifier.fillMaxSize().background(CinemaDark),
        contentAlignment = Alignment.Center
    ) {
        if (!hasPermissions) {
            Text(
                "Grant Camera & Mic permissions to start PanFrame",
                color = Color.White,
                modifier = Modifier.padding(24.dp)
            )
            return@Box
        }

        // Preview and overlay share ONE rect with the recorded frame's aspect ratio,
        // so box position on screen == crop position in the export.
        Box(modifier = Modifier.aspectRatio(frameAspect)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            PanDottedOverlay(
                isLandscapeMode = effectiveLandscape,
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
            Row(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 8.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = CinemaSurface, shape = RoundedCornerShape(16.dp)) {
                    Text(
                        text = if (is4KSupported) {
                            if (effectiveLandscape) "4K WIDE + SHORTS BOX" else "4K SHORTS + YOUTUBE BOX"
                        } else {
                            if (effectiveLandscape) "WIDE 16:9 + SHORTS BOX" else "SHORTS 9:16 + YOUTUBE BOX"
                        },
                        color = CinemaAmber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                if (isRecording) {
                    Surface(color = CinemaRed, shape = RoundedCornerShape(16.dp)) {
                        Text(
                            text = "● %02d:%02d".format(recSeconds / 60, recSeconds % 60),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                } else if (exportStatus.pendingCount > 0) {
                    Surface(color = CinemaSurface, shape = RoundedCornerShape(16.dp)) {
                        Text(
                            text = "Exporting (${exportStatus.pendingCount})...",
                            color = Color.Cyan,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                if (minZoom < 0.95f) {
                    Surface(
                        color = if (currentZoom < 0.95f) CinemaAmber else CinemaSurface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable {
                            val nextZ = if (currentZoom < 0.95f) 1.0f else minZoom.coerceAtLeast(0.5f)
                            currentZoom = nextZ
                            activeCamera?.cameraControl?.setZoomRatio(nextZ)
                        }
                    ) {
                        Text(
                            text = if (currentZoom < 0.95f) "0.5x UW" else "1.0x",
                            color = if (currentZoom < 0.95f) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(if (effectiveLandscape) Alignment.CenterEnd else Alignment.BottomCenter)
                    .padding(bottom = 16.dp, end = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(color = CinemaSurface, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        text = if (effectiveLandscape) "Drag yellow box left / right" else "Drag yellow box up / down",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    if (!isRecording && lastSidecar != null) {
                        OutlinedButton(onClick = { showRePan = true }) {
                            Text("Redo Pan", color = CinemaAmber)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(78.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(8.dp)
                            .clip(if (isRecording) RoundedCornerShape(14.dp) else CircleShape)
                            .background(CinemaRed)
                            .clickable {
                                if (isRecording) activeRecording?.stop() else startRecording()
                            }
                    )
                }
            }
        }
    }
}
