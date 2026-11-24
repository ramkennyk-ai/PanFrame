package com.panframe.camera.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Matrix
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.transformer.*
import com.panframe.camera.model.BoxKeyframe
import com.panframe.camera.model.PanSessionSidecar
import com.panframe.camera.model.PanSmoother
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

data class ExportUiStatus(
    val pendingCount: Int = 0,
    val isExporting: Boolean = false,
    val lastFinishedName: String? = null,
    val lastError: String? = null
)

@OptIn(UnstableApi::class)
class PanExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeTransformer: Transformer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafe("Preparing dual-video export...")
        val sidecarPath = intent?.getStringExtra(EXTRA_SIDECAR_PATH)
        if (!sidecarPath.isNullOrBlank()) {
            queue.add(sidecarPath)
            updateState()
        }
        if (intent?.action == ACTION_RESUME_QUEUE) isCameraBusy = false
        processNextIfIdle()
        return START_NOT_STICKY
    }

    private fun processNextIfIdle() {
        if (activeTransformer != null || isCameraBusy) return
        val nextPath = queue.poll() ?: run {
            updateState(exporting = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val sidecarFile = File(nextPath)
        val sidecar = runCatching {
            Json.decodeFromString<PanSessionSidecar>(sidecarFile.readText())
        }.getOrNull() ?: run { processNextIfIdle(); return }

        updateState(exporting = true)
        startForegroundSafe("Rendering reframed HD video...")

        val smoothed = PanSmoother.smoothKeyframes(sidecar.keyframes)
        val outW = if (sidecar.isLandscapeCapture) 1080 else 1920
        val outH = if (sidecar.isLandscapeCapture) 1920 else 1080
        val tempOut = File(cacheDir, "pan_crop_${System.currentTimeMillis()}.mp4")
        

        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(File(sidecar.rawVideoPath).toURI().toString()))
            .setEffects(Effects(emptyList(), LivePanEffects.buildEffects(sidecar.isLandscapeCapture, smoothed)))
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(14_000_000).build())
            .build()

        val transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    activeTransformer = null
                    scope.launch(Dispatchers.IO) {
                        val suffix = if (sidecar.isLandscapeCapture) "9x16_Reels" else "16x9_YouTube"
                        val saved = saveToMovies(tempOut, suffix)
                        tempOut.delete()
                        _status.value = _status.value.copy(lastFinishedName = saved, lastError = null)
                        withContext(Dispatchers.Main) { processNextIfIdle() }
                    }
                }
                override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                    activeTransformer = null
                    tempOut.delete()
                    _status.value = _status.value.copy(lastError = e.localizedMessage ?: "Export error")
                    processNextIfIdle()
                }
            }).build()

        activeTransformer = transformer
        transformer.start(editedItem, tempOut.absolutePath)
    }

    private fun saveToMovies(source: File, suffix: String): String {
        val fileName = "PanFrame_${System.currentTimeMillis()}_$suffix.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PanFrame")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return fileName
        contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
        return fileName
    }

    private fun updateState(exporting: Boolean = activeTransformer != null) {
        _status.value = _status.value.copy(
            pendingCount = queue.size + (if (exporting) 1 else 0),
            isExporting = exporting
        )
    }

    private fun startForegroundSafe(text: String) {
        val channelId = "panframe_export"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "PanFrame Exports", NotificationManager.IMPORTANCE_LOW))
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("PanFrame Dual Video")
            .setContentText(text)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            startForeground(101, notif)
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val EXTRA_SIDECAR_PATH = "extra_sidecar_path"
        const val ACTION_RESUME_QUEUE = "action_resume_queue"
        private val queue = ConcurrentLinkedQueue<String>()
        @Volatile private var isCameraBusy = false
        private val _status = MutableStateFlow(ExportUiStatus())
        val status = _status.asStateFlow()

        fun enqueue(context: Context, sidecarPath: String) {
            val i = Intent(context, PanExportService::class.java).putExtra(EXTRA_SIDECAR_PATH, sidecarPath)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun setCameraBusy(context: Context, busy: Boolean) {
            isCameraBusy = busy
            if (!busy && queue.isNotEmpty()) {
                val i = Intent(context, PanExportService::class.java).setAction(ACTION_RESUME_QUEUE)
                androidx.core.content.ContextCompat.startForegroundService(context, i)
            }
        }
    }
}

@OptIn(UnstableApi::class)
class LivePanCropEffect(
    private val isLandscapeCapture: Boolean,
    private val outWidth: Int,
    private val outHeight: Int,
    private val keyframes: List<BoxKeyframe>
) : MatrixTransformation {
    private val ratio = 81f / 256f
    private val halfSpan = ratio / 2f
    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(outWidth, outHeight)
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val (rawX, rawY) = PanSmoother.sampleAt(keyframes, presentationTimeUs)
        return Matrix().apply {
            if (isLandscapeCapture) {
                val cx = rawX.coerceIn(halfSpan, 1f - halfSpan)
                postScale(1f / ratio, 1f)
                postTranslate((0.5f - cx) * 2f * (1f / ratio), 0f)
            } else {
                val cy = rawY.coerceIn(halfSpan, 1f - halfSpan)
                postScale(1f, 1f / ratio)
                postTranslate(0f, (cy - 0.5f) * 2f * (1f / ratio))
            }
        }
    }
}
