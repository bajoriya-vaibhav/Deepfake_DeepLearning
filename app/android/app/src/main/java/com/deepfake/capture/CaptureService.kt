package com.deepfake.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.*

/**
 * Foreground service that manages the entire capture lifecycle.
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "deepfake_capture_channel"
        private const val NOTIFICATION_ID = 1001
        private const val FRAME_INTERVAL_MS = 1000L

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
        const val STATUS_ERROR = "Error"
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
        Log.i(TAG, "=== CaptureService onCreate ===")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "=== onStartCommand called ===")

        // CRITICAL: Must call startForeground FIRST on Android 12+
        // before doing ANYTHING else (including validation/stopSelf)
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            Log.i(TAG, "startForeground succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground FAILED", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null) {
            Log.e(TAG, "Intent is null, stopping service")
            broadcastStatus(STATUS_ERROR, "No intent received")
            stopSelf()
            return START_NOT_STICKY
        }

        // NOTE: Activity.RESULT_OK == -1, so we use MIN_VALUE as the "missing" sentinel
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: ""

        Log.i(TAG, "resultCode=$resultCode, serverUrl='$serverUrl'")

        // API 33+ requires the typed getParcelableExtra overload
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        Log.i(TAG, "resultData=${if (resultData != null) "present" else "NULL"}")

        if (resultCode == Int.MIN_VALUE || resultData == null || serverUrl.isBlank()) {
            Log.e(TAG, "Missing extras! resultCode=$resultCode, data=$resultData, url='$serverUrl'")
            broadcastStatus(STATUS_ERROR, "Missing capture data")
            stopSelf()
            return START_NOT_STICKY
        }

        // Initialize everything in a try-catch
        try {
            initializeCapture(resultCode, resultData, serverUrl)
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: Failed to initialize capture", e)
            broadcastStatus(STATUS_ERROR, e.message ?: "Init failed")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun initializeCapture(resultCode: Int, resultData: Intent, serverUrl: String) {
        // 1. Create MediaProjection
        Log.i(TAG, "Creating MediaProjection...")
        mediaProjectionHelper = MediaProjectionHelper(this)
        val projection = mediaProjectionHelper!!.createProjection(resultCode, resultData)

        if (projection == null) {
            throw RuntimeException("MediaProjection creation returned null")
        }
        Log.i(TAG, "MediaProjection created OK")

        // 2. Get screen metrics
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val captureWidth = metrics.widthPixels
        val captureHeight = metrics.heightPixels
        val density = metrics.densityDpi
        Log.i(TAG, "Screen: ${captureWidth}x${captureHeight} density=$density")

        // 3. Initialize video capturer
        Log.i(TAG, "Starting VideoFrameCapturer...")
        videoFrameCapturer = VideoFrameCapturer(projection, captureWidth, captureHeight, density)
        try {
            videoFrameCapturer?.start()
            Log.i(TAG, "VideoFrameCapturer started OK")
        } catch (e: Exception) {
            Log.e(TAG, "VideoFrameCapturer.start() failed", e)
            // Continue without video — audio might still work
            videoFrameCapturer = null
        }

        // 4. Initialize audio capturer
        Log.i(TAG, "Starting AudioCapturer...")
        audioCapturer = AudioCapturer(projection)
        try {
            audioCapturer?.start(serviceScope)
            Log.i(TAG, "AudioCapturer started OK")
        } catch (e: Exception) {
            Log.e(TAG, "AudioCapturer.start() failed", e)
            // Continue without audio — video might still work
            audioCapturer = null
        }

        // 5. API client
        apiClient = ApiClient(serverUrl)

        // 6. Overlay (optional)
        try {
            if (Settings.canDrawOverlays(this)) {
                overlayManager = OverlayManager(this)
                overlayManager?.show()
                overlayManager?.updateStatus("📡 Capturing…")
                Log.i(TAG, "Overlay shown")
            } else {
                Log.i(TAG, "No overlay permission, skipping overlay")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Overlay failed (non-critical)", e)
        }

        // 7. Start capture loop
        broadcastStatus(STATUS_CAPTURING)
        startCaptureLoop()
        Log.i(TAG, "=== Capture fully initialized ===")
    }

    private fun startCaptureLoop() {
        captureJob = serviceScope.launch {
            // Wait a moment for capturers to stabilize
            delay(500)

            while (isActive) {
                delay(FRAME_INTERVAL_MS)

                try {
                    val frame = videoFrameCapturer?.consumeLatestFrame()
                    val audio = audioCapturer?.consumeLatestSegment()

                    Log.d(TAG, "Loop: frame=${frame?.size ?: "null"} bytes, audio=${audio?.size ?: "null"} bytes")

                    if (frame != null || audio != null) {
                        broadcastStatus(STATUS_ANALYZING)
                        overlayManager?.updateStatus("🔍 Analyzing…")

                        val result = apiClient?.sendForPrediction(frame, audio)

                        if (result != null) {
                            Log.i(TAG, "Result: ${result.prediction} (${result.confidence})")
                            broadcastResult(result.prediction, result.confidence)
                            overlayManager?.updateResult(result.prediction, result.confidence)
                        } else {
                            broadcastStatus(STATUS_CAPTURING)
                            overlayManager?.updateStatus("📡 Capturing…")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in capture loop iteration", e)
                    // Don't crash — continue looping
                }
            }
        }
    }

    private fun broadcastStatus(status: String, errorMsg: String? = null) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            if (errorMsg != null) putExtra("error_msg", errorMsg)
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
        Log.i(TAG, "=== CaptureService onDestroy ===")
        captureJob?.cancel()
        try { videoFrameCapturer?.stop() } catch (e: Exception) { Log.e(TAG, "Error stopping video", e) }
        try { audioCapturer?.stop() } catch (e: Exception) { Log.e(TAG, "Error stopping audio", e) }
        try { mediaProjectionHelper?.stop() } catch (e: Exception) { Log.e(TAG, "Error stopping projection", e) }
        try { overlayManager?.hide() } catch (e: Exception) { Log.e(TAG, "Error hiding overlay", e) }
        serviceScope.cancel()
        broadcastStatus(STATUS_IDLE)
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
