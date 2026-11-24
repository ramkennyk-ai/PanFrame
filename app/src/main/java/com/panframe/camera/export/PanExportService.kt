package com.panframe.camera.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import com.panframe.camera.model.PanJson
import com.panframe.camera.model.PanSessionSidecar
import com.panframe.camera.model.PanSmoother
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
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
    private var working = false

    private class ExportJob(val path: String, val sidecar: PanSessionSidecar, val upW: Int, val upH: Int)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafe("Preparing cropped video...")
        scope.launch {
            runCatching { processNextIfIdle() }.onFailure { err ->
                Toast.makeText(applicationContext, "Export init error: ${err.message}", Toast.LENGTH_LONG).show()
                working = false
                stopSelfSafe()
            }
        }
        return START_NOT_STICKY
    }

    private fun processNextIfIdle() {
        if (working || isCameraBusy) return
        val nextPath = queue.poll()
        if (nextPath == null) {
            current = null
            updateState()
            stopSelfSafe()
            return
        }
        working = true
        current = nextPath
        updateState()
        startForegroundSafe("Rendering cropped video...")
        scope.launch {
            val job = withContext(Dispatchers.IO) { loadJob(nextPath) }
            if (job == null) finishCurrent() else startTransform(job)
        }
    }

    private fun finishCurrent() {
        activeTransformer = null
        working = false
        current = null
        updateState()
        processNextIfIdle()
    }

    private fun loadJob(path: String): ExportJob? {
        val file = File(path)
        val sidecar = PanJson.decode(runCatching { file.readText() }.getOrDefault("")) ?: return null
        if (sidecar.exported || sidecar.failed) return null

        var w = 0
        var h = 0
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, Uri.parse(sidecar.videoUri))
            val rw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rh = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            // upright dimensions (Media3 hands effects upright frames)
            if (rot == 90 || rot == 270) { w = rh; h = rw } else { w = rw; h = rh }
        } catch (e: Exception) {
            // source deleted or unreadable
        } finally {
            runCatching { retriever.release() }
        }
        if (w <= 0 || h <= 0) {
            markFinished(path, sidecar.revision, failed = true)
            _status.value = _status.value.copy(lastError = "Original video not readable")
            return null
        }
        return ExportJob(path, sidecar, w, h)
    }

    private fun startTransform(job: ExportJob) {
        val sidecar = job.sidecar
        val smoothed = PanSmoother.smoothKeyframes(sidecar.keyframes)
        val out = LivePanEffects.outputSize(sidecar.isLandscapeCapture, job.upW, job.upH)
        val effects = LivePanEffects.buildEffects(sidecar.isLandscapeCapture, out, smoothed)
        val tempOut = File(cacheDir, "pan_crop_${System.currentTimeMillis()}.mp4")

        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(sidecar.videoUri)))
            .setEffects(Effects(emptyList(), effects))
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(LivePanEffects.bitrateFor(out)).build()
            )
            .setEnableFallback(true)
            .build()

        val transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            val r = runCatching { saveToMovies(tempOut, sidecar) }
                            tempOut.delete()
                            markFinished(job.path, sidecar.revision, failed = r.isFailure)
                            r
                        }
                        result.onSuccess { name ->
                            Toast.makeText(applicationContext, "Saved: $name", Toast.LENGTH_LONG).show()
                            _status.value = _status.value.copy(lastFinishedName = name, lastError = null)
                        }.onFailure { e ->
                            Toast.makeText(applicationContext, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                            _status.value = _status.value.copy(lastError = e.message)
                        }
                        finishCurrent()
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    val msg = exportException.localizedMessage ?: "Codec error"
                    tempOut.delete()
                    markFinished(job.path, sidecar.revision, failed = true)
                    Toast.makeText(applicationContext, "Export error: $msg", Toast.LENGTH_LONG).show()
                    _status.value = _status.value.copy(lastError = msg)
                    finishCurrent()
                }
            }).build()

        activeTransformer = transformer
        transformer.start(editedItem, tempOut.absolutePath)
    }

    /** Only touches the sidecar if no newer re-pan revision replaced it while we were exporting. */
    private fun markFinished(path: String, revision: Int, failed: Boolean) {
        runCatching {
            val file = File(path)
            val latest = PanJson.decode(file.readText()) ?: return
            if (latest.revision != revision) return
            file.writeText(PanJson.encode(latest.copy(exported = !failed, failed = failed)))
        }
    }

    private fun saveToMovies(source: File, sidecar: PanSessionSidecar): String {
        val other = if (sidecar.isLandscapeCapture) "9x16" else "16x9"
        val rev = if (sidecar.revision > 0) "_r${sidecar.revision}" else ""
        val displayName = "${sidecar.baseName}_${other}_Box$rev"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PanFrame")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("Cannot open output stream")
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return displayName
    }

    private fun updateState() {
        _status.value = _status.value.copy(
            pendingCount = queue.size + (if (working) 1 else 0),
            isExporting = working
        )
    }

    private fun stopSelfSafe() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    private fun startForegroundSafe(text: String) {
        runCatching {
            val channelId = "panframe_export"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(channelId, "PanFrame Exports", NotificationManager.IMPORTANCE_LOW))
            val notif = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("PanFrame Dual Video")
                .setContentText(text)
                .setOngoing(true)
                .build()
            startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    override fun onDestroy() {
        activeTransformer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_RESUME_QUEUE = "action_resume_queue"
        private val queue = ConcurrentLinkedQueue<String>()
        @Volatile private var isCameraBusy = false
        @Volatile private var current: String? = null
        private val _status = MutableStateFlow(ExportUiStatus())
        val status = _status.asStateFlow()

        fun sidecarDir(context: Context): File =
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir

        /** force = true lets a re-pan re-queue the session that is currently rendering. */
        fun enqueue(context: Context, sidecarPath: String, force: Boolean = false) {
            if (!queue.contains(sidecarPath) && (force || sidecarPath != current)) queue.add(sidecarPath)
            _status.value = _status.value.copy(pendingCount = queue.size + (if (current != null) 1 else 0))
            if (!isCameraBusy) start(context, null)
        }

        fun setCameraBusy(context: Context, busy: Boolean) {
            isCameraBusy = busy
            if (!busy && queue.isNotEmpty()) start(context, ACTION_RESUME_QUEUE)
        }

        /** Re-queue sessions whose export was interrupted (process death, reboot, crash). */
        suspend fun resumePending(context: Context) {
            val pending = withContext(Dispatchers.IO) {
                sidecarDir(context).listFiles { f -> f.extension == "json" }
                    ?.filter { f ->
                        val s = PanJson.decode(runCatching { f.readText() }.getOrDefault(""))
                        s != null && !s.exported && !s.failed
                    }.orEmpty()
            }
            pending.forEach { enqueue(context, it.absolutePath) }
        }

        private fun start(context: Context, action: String?) {
            runCatching {
                val i = Intent(context, PanExportService::class.java)
                if (action != null) i.action = action
                ContextCompat.startForegroundService(context, i)
            }.onFailure { err ->
                Toast.makeText(context.applicationContext, "Queue error: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
