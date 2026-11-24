package com.panframe.camera.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
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
        startForegroundSafe("Saving cropped box video...")
        val sidecarPath = intent?.getStringExtra(EXTRA_SIDECAR_PATH)
        if (!sidecarPath.isNullOrBlank()) {
            queue.add(sidecarPath)
            updateState()
        }
        if (intent?.action == ACTION_RESUME_QUEUE) isCameraBusy = false
        scope.launch {
            delay(700L)
            runCatching { processNextIfIdle() }.onFailure { err ->
                Toast.makeText(applicationContext, "Export init error: ${err.message}", Toast.LENGTH_LONG).show()
                stopSelfSafe()
            }
        }
        return START_NOT_STICKY
    }

    private fun processNextIfIdle() {
        if (activeTransformer != null || isCameraBusy) return
        val nextPath = queue.poll() ?: run {
            updateState(exporting = false)
            stopSelfSafe()
            return
        }
        val sidecar = runCatching {
            Json.decodeFromString<PanSessionSidecar>(File(nextPath).readText())
        }.getOrNull() ?: run { processNextIfIdle(); return }

        updateState(exporting = true)
        startForegroundSafe("Rendering Shorts/Box video...")

        var fixDeg = 0f
        val retriever = MediaMetadataRetriever()
        runCatching {
            retriever.setDataSource(sidecar.rawVideoPath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val upW = if (rot == 90 || rot == 270) h else w
            val upH = if (rot == 90 || rot == 270) w else h
            if (sidecar.isLandscapeCapture && upH > upW) fixDeg = 90f
        }
        runCatching { retriever.release() }

        val smoothed = PanSmoother.smoothKeyframes(sidecar.keyframes)
        val tempOut = File(cacheDir, "pan_crop_${System.currentTimeMillis()}.mp4")
        val effects = LivePanEffects.buildEffects(sidecar.isLandscapeCapture, fixDeg, smoothed)

        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(sidecar.rawVideoPath))))
            .setEffects(Effects(emptyList(), effects))
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(this).setEnableFallback(true).build()

        val transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    activeTransformer = null
                    scope.launch(Dispatchers.IO) {
                        val suffix = if (sidecar.isLandscapeCapture) "9x16_ShortsBox" else "16x9_YouTubeBox"
                        val saved = saveToMovies(tempOut, suffix)
                        tempOut.delete()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(applicationContext, "Saved Box Video: $saved", Toast.LENGTH_LONG).show()
                            _status.value = _status.value.copy(lastFinishedName = saved, lastError = null)
                            processNextIfIdle()
                        }
                    }
                }
                override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                    activeTransformer = null
                    tempOut.delete()
                    val msg = e.localizedMessage ?: "Codec error"
                    Toast.makeText(applicationContext, "Box Export Error: $msg", Toast.LENGTH_LONG).show()
                    _status.value = _status.value.copy(lastError = msg)
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
        val pubPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath + "/PanFrame/$fileName"
        runCatching { MediaScannerConnection.scanFile(this, arrayOf(pubPath), arrayOf("video/mp4"), null) }
        return fileName
    }

    private fun updateState(exporting: Boolean = activeTransformer != null) {
        _status.value = _status.value.copy(pendingCount = queue.size + (if (exporting) 1 else 0), isExporting = exporting)
    }

    private fun stopSelfSafe() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    private fun startForegroundSafe(text: String) {
        runCatching {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(101, notif)
            }
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
            runCatching {
                val i = Intent(context, PanExportService::class.java).putExtra(EXTRA_SIDECAR_PATH, sidecarPath)
                androidx.core.content.ContextCompat.startForegroundService(context, i)
            }.onFailure { err ->
                Toast.makeText(context.applicationContext, "Queue error: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }

        fun setCameraBusy(context: Context, busy: Boolean) {
            isCameraBusy = busy
            if (!busy && queue.isNotEmpty()) {
                runCatching {
                    val i = Intent(context, PanExportService::class.java).setAction(ACTION_RESUME_QUEUE)
                    androidx.core.content.ContextCompat.startForegroundService(context, i)
                }
            }
        }
    }
}
