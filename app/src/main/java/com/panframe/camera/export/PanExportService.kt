
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
