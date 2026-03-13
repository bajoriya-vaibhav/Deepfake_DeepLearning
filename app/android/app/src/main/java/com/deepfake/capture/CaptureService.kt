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
 * The overlay (managed by OverlayManager) provides Start/Stop/Close controls.
 * On service start: shows overlay in Idle state.
 * On overlay Start: begins capture loop.
 * On overlay Stop: stops capture loop, resets to Idle.
 * On overlay Close: stops service entirely.
 */
class CaptureService : Service(), OverlayManager.OverlayCallback {

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "deepfake_capture_channel"
        private const val NOTIFICATION_ID = 1001

        private const val FRAME_INTERVAL_MS = 1000L

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SERVER_URL = "server_url"

        // Status constants (kept for internal use)
        const val EXTRA_STATUS = "status"
        const val EXTRA_PREDICTION = "prediction"
        const val EXTRA_CONFIDENCE = "confidence"
        const val ACTION_STATUS_UPDATE = "com.deepfake.capture.STATUS_UPDATE"
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

    // Store projection data for lazy initialization
    private var resultCode: Int = Int.MIN_VALUE
    private var resultData: Intent? = null
    private var serverUrl: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "=== CaptureService onCreate ===")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "=== onStartCommand called ===")

        // CRITICAL: Must call startForeground FIRST on Android 12+
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
            stopSelf()
            return START_NOT_STICKY
        }

        // Store projection data for later use when Start is pressed
        resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: ""

        // API 33+ requires the typed getParcelableExtra overload
        resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        Log.i(TAG, "resultCode=$resultCode, serverUrl='$serverUrl', resultData=${if (resultData != null) "present" else "NULL"}")

        if (resultCode == Int.MIN_VALUE || resultData == null) {
            Log.e(TAG, "Missing capture data, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // Show overlay immediately in Idle state
        showOverlay()

        return START_NOT_STICKY
    }

    // ─── Overlay Setup ─────────────────────────────────────────────

    private fun showOverlay() {
        try {
            if (Settings.canDrawOverlays(this)) {
                overlayManager = OverlayManager(this)
                overlayManager?.setCallback(this)
                overlayManager?.show()
                Log.i(TAG, "Overlay shown in Idle state")
            } else {
                Log.w(TAG, "No overlay permission — service running without overlay")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
        }
    }

    // ─── OverlayCallback Implementation ────────────────────────────

    override fun onStartCapture(serverUrl: String) {
        Log.i(TAG, "Overlay: Start pressed, url=$serverUrl")
        this.serverUrl = serverUrl

        // Initialize capture components
        try {
            initializeCapture()
            overlayManager?.setCapturingState(true)
            overlayManager?.updateStatus("📡 Capturing…")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            overlayManager?.updateStatus("Error: ${e.message}")
            overlayManager?.setCapturingState(false)
        }
    }

    override fun onStopCapture() {
        Log.i(TAG, "Overlay: Stop pressed")
        stopCapturing()
        overlayManager?.setCapturingState(false)
        overlayManager?.updateStatus("Idle")
    }

    override fun onCloseOverlay() {
        Log.i(TAG, "Overlay: Close pressed")
        stopSelf()
    }

    // ─── Capture Lifecycle ─────────────────────────────────────────

    private fun initializeCapture() {
        val data = resultData ?: throw RuntimeException("No projection data available")

        // 1. Create MediaProjection
        Log.i(TAG, "Creating MediaProjection...")
        mediaProjectionHelper = MediaProjectionHelper(this)
        val projection = mediaProjectionHelper!!.createProjection(resultCode, data)
            ?: throw RuntimeException("MediaProjection creation returned null")
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
            audioCapturer = null
        }

        // 5. API client
        apiClient = ApiClient(serverUrl)

        // 6. Start capture loop
        startCaptureLoop()
        Log.i(TAG, "=== Capture fully initialized ===")
    }

    private fun startCaptureLoop() {
        captureJob = serviceScope.launch {
            delay(500) // Wait for capturers to stabilize

            while (isActive) {
                delay(FRAME_INTERVAL_MS)

                try {
                    val frame = videoFrameCapturer?.consumeLatestFrame()
                    val audio = audioCapturer?.consumeLatestSegment()

                    Log.d(TAG, "Loop: frame=${frame?.size ?: "null"} bytes, audio=${audio?.size ?: "null"} bytes")

                    if (frame != null || audio != null) {
                        overlayManager?.updateStatus("🔍 Analyzing…")

                        val result = apiClient?.sendForPrediction(frame, audio)

                        if (result != null) {
                            Log.i(TAG, "Result: ${result.prediction} (${result.confidence})")
                            overlayManager?.updateResult(result.prediction, result.confidence)
                        } else {
                            overlayManager?.updateStatus("📡 Capturing…")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in capture loop iteration", e)
                }
            }
        }
    }

    private fun stopCapturing() {
        captureJob?.cancel()
        captureJob = null
        try { videoFrameCapturer?.stop() } catch (e: Exception) { Log.e(TAG, "Error stopping video", e) }
        try { audioCapturer?.stop() } catch (e: Exception) { Log.e(TAG, "Error stopping audio", e) }
        try { mediaProjectionHelper?.stop() } catch (e: Exception) { Log.e(TAG, "Error stopping projection", e) }
        videoFrameCapturer = null
        audioCapturer = null
        mediaProjectionHelper = null
        apiClient = null
    }

    // ─── Service Lifecycle ─────────────────────────────────────────

    override fun onDestroy() {
        Log.i(TAG, "=== CaptureService onDestroy ===")
        stopCapturing()
        try { overlayManager?.hide() } catch (e: Exception) { Log.e(TAG, "Error hiding overlay", e) }
        overlayManager = null
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─── Notification ──────────────────────────────────────────────

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
