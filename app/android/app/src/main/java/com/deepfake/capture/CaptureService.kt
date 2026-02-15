package com.deepfake.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.*

/**
 * Foreground service that manages the entire capture lifecycle:
 *  1. Obtains MediaProjection from the result intent passed by MainActivity
 *  2. Starts VideoFrameCapturer and AudioCapturer
 *  3. Runs a capture loop (1 fps) sending data to the backend via ApiClient
 *  4. Broadcasts results back to MainActivity and updates OverlayManager
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "deepfake_capture_channel"
        private const val NOTIFICATION_ID = 1001
        private const val FRAME_INTERVAL_MS = 1000L // 1 fps

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SERVER_URL = "server_url"

        const val ACTION_STATUS_UPDATE = "com.deepfake.capture.STATUS_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PREDICTION = "prediction"
        const val EXTRA_CONFIDENCE = "confidence"

        const val STATUS_CAPTURING = "Capturing"
        const val STATUS_ANALYZING = "Analyzing"
        const val STATUS_IDLE = "Idle"
    }

    private var mediaProjectionHelper: MediaProjectionHelper? = null
    private var videoFrameCapturer: VideoFrameCapturer? = null
    private var audioCapturer: AudioCapturer? = null
    private var apiClient: ApiClient? = null
    private var overlayManager: OverlayManager? = null
    private var captureJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "CaptureService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: ""

        if (resultCode == -1 || resultData == null || serverUrl.isBlank()) {
            Log.e(TAG, "Missing required extras")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start as foreground immediately
        startForeground(NOTIFICATION_ID, createNotification())

        // Initialize components
        mediaProjectionHelper = MediaProjectionHelper(this)
        val projection = mediaProjectionHelper!!.createProjection(resultCode, resultData)

        if (projection == null) {
            Log.e(TAG, "Failed to create MediaProjection")
            broadcastStatus(STATUS_IDLE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Get screen metrics
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        // Scale down for efficiency (half resolution)
        val captureWidth = metrics.widthPixels / 2
        val captureHeight = metrics.heightPixels / 2
        val density = metrics.densityDpi

        // Initialize capturers
        videoFrameCapturer = VideoFrameCapturer(projection, captureWidth, captureHeight, density)
        audioCapturer = AudioCapturer(projection)
        apiClient = ApiClient(serverUrl)

        // Start overlay if permission is granted
        if (Settings.canDrawOverlays(this)) {
            overlayManager = OverlayManager(this)
            overlayManager?.show()
        }

        // Start capturing
        videoFrameCapturer?.start()
        audioCapturer?.start(serviceScope)
        broadcastStatus(STATUS_CAPTURING)
        overlayManager?.updateStatus("📡 Capturing…")

        // Start the capture loop
        startCaptureLoop()

        Log.i(TAG, "Capture started: ${captureWidth}x${captureHeight} @ $serverUrl")
        return START_NOT_STICKY
    }

    private fun startCaptureLoop() {
        captureJob = serviceScope.launch {
            while (isActive) {
                delay(FRAME_INTERVAL_MS)

                val frame = videoFrameCapturer?.consumeLatestFrame()
                val audio = audioCapturer?.consumeLatestSegment()

                if (frame != null || audio != null) {
                    broadcastStatus(STATUS_ANALYZING)
                    overlayManager?.updateStatus("🔍 Analyzing…")

                    val result = apiClient?.sendForPrediction(frame, audio)

                    if (result != null) {
                        broadcastResult(result.prediction, result.confidence)
                        overlayManager?.updateResult(result.prediction, result.confidence)
                    } else {
                        broadcastStatus(STATUS_CAPTURING)
                        overlayManager?.updateStatus("📡 Capturing…")
                    }
                }
            }
        }
    }

    private fun broadcastStatus(status: String) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastResult(prediction: String, confidence: Float) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, "Result")
            putExtra(EXTRA_PREDICTION, prediction)
            putExtra(EXTRA_CONFIDENCE, confidence)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        captureJob?.cancel()
        videoFrameCapturer?.stop()
        audioCapturer?.stop()
        mediaProjectionHelper?.stop()
        overlayManager?.hide()
        serviceScope.cancel()
        broadcastStatus(STATUS_IDLE)
        Log.i(TAG, "CaptureService destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
