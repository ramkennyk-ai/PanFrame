package com.panframe.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.panframe.camera.export.PanExportService
import com.panframe.camera.model.BoxKeyframe
import com.panframe.camera.model.PanSessionSidecar
import com.panframe.camera.ui.PanDottedOverlay
import com.panframe.camera.ui.RePanScreen
import com.panframe.camera.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PanFrameTheme {
                PanFrameApp()
            }
        }
    }
}

@OptIn(ExperimentalCamera2Interop::class)
@SuppressLint("MissingPermission")
@Composable
fun PanFrameApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val exportStatus by PanExportService.status.collectAsState()

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

    var isLandscapePhone by remember { mutableStateOf(false) }
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
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }

    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN || isRecording) return
                isLandscapePhone = (orientation in 60..120) || (orientation in 240..300)
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(hasPermissions) {
        if (!hasPermissions) return@LaunchedEffect
        val provider = ProcessCameraProvider.getInstance(context).get()
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val camInfo = provider.availableCameraInfos.firstOrNull()
        val caps = camInfo?.let { Recorder.getVideoCapabilities(it) }
        is4KSupported = caps?.getSupportedQualities( androidx.camera.core.DynamicRange.SDR )?.contains(Quality.UHD) == true

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.UHD, Quality.FHD, Quality.HD)))
            .build()
        val vidCapBuilder = VideoCapture.Builder(recorder)
        Camera2Interop.Extender(vidCapBuilder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30)
        )
        val vidCap = vidCapBuilder.build()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        provider.unbindAll()
        val groupBuilder = UseCaseGroup.Builder().addUseCase(preview).addUseCase(vidCap)
        previewView.viewPort?.let { groupBuilder.setViewPort(it) }
        val cam = provider.bindToLifecycle(lifecycleOwner, selector, groupBuilder.build())
        activeCamera = cam
        videoCapture = vidCap
        minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1.0f
    }
    val effectiveLandscape = if (isRecording) lockedLandscape else isLandscapePhone

    if (showRePan && lastSidecar != null) {
        val (jsonFile, sidecarObj) = lastSidecar!!
        RePanScreen(
            sidecar = sidecarObj,
            onSaveAndExport = { updated ->
                jsonFile.writeText(Json.encodeToString(updated))
                showRePan = false
                PanExportService.setCameraBusy(context, false)
                PanExportService.enqueue(context, jsonFile.absolutePath)
            },
            onCancel = {
                showRePan = false
                PanExportService.setCameraBusy(context, false)
                PanExportService.enqueue(context, jsonFile.absolutePath)
            }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(CinemaDark)) {
        if (!hasPermissions) {
            Text(
                "Grant Camera & Mic permissions to start PanFrame",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
            return@Box
        }

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

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 42.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = CinemaSurface, shape = RoundedCornerShape(16.dp)) {
                Text(
                    text = if (is4KSupported) "4K DUAL-HD" else "1080p SMART-HD",
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
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (effectiveLandscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { scope.launch { animX.animateTo(0.22f, tween(420)) } }) { Text("◀ Left") }
                    FilledTonalButton(onClick = { scope.launch { animX.animateTo(0.50f, tween(420)) } }) { Text("Center") }
                    FilledTonalButton(onClick = { scope.launch { animX.animateTo(0.78f, tween(420)) } }) { Text("Right ▶") }
                }
                Spacer(Modifier.height(14.dp))
            }

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
                            if (isRecording) {
                                activeRecording?.stop()
                            } else {
                                val vc = videoCapture ?: return@clickable
                                PanExportService.setCameraBusy(context, true)
                                lockedLandscape = isLandscapePhone
                                vc.targetRotation = if (lockedLandscape) Surface.ROTATION_90 else Surface.ROTATION_0
                                keyframes.clear()
                                recSeconds = 0L

                                val rawFile = File(
                                    context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                                    "PanFrame_RAW_${System.currentTimeMillis()}.mp4"
                                )
                                val outputOpts = FileOutputOptions.Builder(rawFile).build()

                                activeRecording = vc.output
                                    .prepareRecording(context, outputOpts)
                                    .withAudioEnabled()
                                    .start(ContextCompat.getMainExecutor(context)) { event ->
                                        when (event) {
                                            is VideoRecordEvent.Start -> isRecording = true
                                            is VideoRecordEvent.Status -> {
                                                val us = event.recordingStats.recordedDurationNanos / 1000L
                                                recSeconds = us / 1_000_000L
                                                keyframes.add(BoxKeyframe(us, animX.value, animY.value))
                                            }
                                            is VideoRecordEvent.Finalize -> {
                                                isRecording = false
                                                activeRecording = null
                                                if (!event.hasError()) {
                                                    saveRawCopyToGallery(context, rawFile, lockedLandscape)
                                                    val sidecar = PanSessionSidecar(
                                                        rawVideoPath = rawFile.absolutePath,
                                                        isLandscapeCapture = lockedLandscape,
                                                        sourceWidth = if (lockedLandscape) 3840 else 2160,
                                                        sourceHeight = if (lockedLandscape) 2160 else 3840,
                                                        keyframes = keyframes.toList()
                                                    )
                                                    val jsonFile = File(rawFile.parentFile, "${rawFile.nameWithoutExtension}.json")
                                                    jsonFile.writeText(Json.encodeToString(sidecar))
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
                        }
                )
            }
        }
    }
}

private fun saveRawCopyToGallery(context: Context, rawFile: File, isLandscape: Boolean) {
    if (!rawFile.exists()) return
    val tag = if (isLandscape) "16x9_Original" else "9x16_Original"
    val name = "PanFrame_${System.currentTimeMillis()}_$tag.mp4"
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PanFrame")
        }
    }
    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
    context.contentResolver.openOutputStream(uri)?.use { out ->
        rawFile.inputStream().use { it.copyTo(out) }
    }
}
