package com.deepfake.detection.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.deepfake.detection.R
import com.deepfake.detection.capture.AudioCapturer
import com.deepfake.detection.capture.RootCaptureManager
import com.deepfake.detection.network.BackendClient
import com.deepfake.detection.ui.OverlayManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class DeepfakeService : Service() {

    private lateinit var overlayManager: OverlayManager
    private val audioCapturer = AudioCapturer()
    private val executor = Executors.newSingleThreadExecutor()
    private var isRunning = false
    private var serverUrl = "http://192.168.1.100:7860/predict" // Default, should be settable
    
    // Telephony
    private lateinit var telephonyManager: TelephonyManager
    
    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayManager(this)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        startForegroundService()
        registerPhoneListener()
    }

    private fun startForegroundService() {
        val channelId = "deepfake_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Deepfake Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.service_notification_title))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("URL")
        if (url != null) serverUrl = url
        
        val action = intent?.action
        if (action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Ensure Root or fall back to Simulation
        val hasRoot = RootCaptureManager.isRootAvailable()
        if (!hasRoot) {
            Log.w("DeepfakeService", "Root unavailable. Switching to SIMULATION MODE.")
        }
        
        startDetectionLoop(hasRoot)
        overlayManager.showOverlay()
        
        if (!hasRoot) {
             overlayManager.updateStatus("SIMULATION MODE", "No Root Access", android.graphics.Color.MAGENTA)
        }
        
        return START_STICKY
    }

    private fun registerPhoneListener() {
        telephonyManager.listen(object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                         // Activate logic if needed specifically for calls
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                         // Pause logic? keeping it always on for now as per "always-on floating overlay"
                    }
                }
            }
        }, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun startDetectionLoop(hasRoot: Boolean) {
        if (isRunning) return
        isRunning = true
        if (hasRoot) audioCapturer.startRecording()
        
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

        executor.execute {
            while (isRunning) {
                try {
                    // Check triggers
                    val isAudioActive = if (hasRoot) {
                         audioManager.isMusicActive || audioManager.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
                    } else {
                        true // Always active in simulation
                    }
                    
                    // Capture
                    val bitmap = if (hasRoot) RootCaptureManager.captureScreenTrace() else RootCaptureManager.generateMockTrace()
                    val audioBytes = if (hasRoot) audioCapturer.readChunk() else ByteArray(1024) // Dummy audio
                    
                    if (bitmap != null) {
                        // Compress Bitmap
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
                        val imageBytes = stream.toByteArray()
                        
                        // Send
                        val statusMsg = if (!hasRoot) "Sending Mock Data..." else if (isAudioActive) "Analyzing Media..." else "Idle (Monitoring)"
                        val startColor = if (!hasRoot) android.graphics.Color.MAGENTA else android.graphics.Color.YELLOW
                        
                        overlayManager.updateStatus(statusMsg, "...", startColor)

                        if (isAudioActive) {
                            BackendClient.sendData(serverUrl, imageBytes, audioBytes) { response ->
                                if (response.contains("Fake", true)) {
                                    overlayManager.updateStatus("POSSIBLE DEEPFAKE", response, android.graphics.Color.RED)
                                } else {
                                    overlayManager.updateStatus("Likely Real", response, android.graphics.Color.GREEN)
                                }
                            }
                        }
                    }
                    
                    Thread.sleep(1000) // 1 FPS
                } catch (e: Exception) {
                    Log.e("DeepfakeService", "Loop error", e)
                }
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        audioCapturer.stopRecording()
        overlayManager.hideOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
