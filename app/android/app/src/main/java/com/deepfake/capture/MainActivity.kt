package com.deepfake.capture

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Main Activity — entry point with Start/Stop buttons, server URL input, and status display.
 * Handles MediaProjection permission flow and communicates with CaptureService.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "deepfake_prefs"
        private const val KEY_SERVER_URL = "server_url"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var resultCard: CardView
    private lateinit var statusIndicator: View
    private lateinit var etServerUrl: TextInputEditText
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton

    private var isCapturing = false

    // MediaProjection permission launcher
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    // Runtime permission launcher
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    // Receives status updates from CaptureService
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(CaptureService.EXTRA_STATUS) ?: return

            when (status) {
                CaptureService.STATUS_CAPTURING -> {
                    updateStatusUI("Capturing…", R.color.status_capturing)
                    resultCard.visibility = View.GONE
                }
                CaptureService.STATUS_ANALYZING -> {
                    updateStatusUI("Analyzing…", R.color.status_analyzing)
                }
                CaptureService.STATUS_IDLE -> {
                    updateStatusUI("Idle", R.color.status_idle)
                    setCapturingState(false)
                }
                "Result" -> {
                    val prediction = intent.getStringExtra(CaptureService.EXTRA_PREDICTION) ?: "Unknown"
                    val confidence = intent.getFloatExtra(CaptureService.EXTRA_CONFIDENCE, 0f)
                    showResult(prediction, confidence)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        tvConfidence = findViewById(R.id.tvConfidence)
        resultCard = findViewById(R.id.resultCard)
        statusIndicator = findViewById(R.id.statusIndicator)
        etServerUrl = findViewById(R.id.etServerUrl)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        // Restore saved server URL
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_SERVER_URL, "http://192.168.1.100:7860")
        etServerUrl.setText(savedUrl)

        // Setup activity result launchers
        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startCaptureService(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                setCapturingState(false)
            }
        }

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                requestScreenCapture()
            } else {
                Toast.makeText(this, "Audio permission required for capture", Toast.LENGTH_SHORT).show()
                setCapturingState(false)
            }
        }

        // Button listeners
        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener { onStopClicked() }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(CaptureService.ACTION_STATUS_UPDATE)
        registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    // ─── Button Handlers ──────────────────────────────────────────

    private fun onStartClicked() {
        // Save the server URL
        val serverUrl = etServerUrl.text?.toString()?.trim() ?: ""
        if (serverUrl.isBlank()) {
            Toast.makeText(this, "Please enter a server URL", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putString(KEY_SERVER_URL, serverUrl).apply()

        setCapturingState(true)

        // Check and request permissions
        val neededPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            requestScreenCapture()
        }
    }

    private fun onStopClicked() {
        stopService(Intent(this, CaptureService::class.java))
        setCapturingState(false)
        updateStatusUI("Idle", R.color.status_idle)
    }

    // ─── MediaProjection Flow ─────────────────────────────────────

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val serverUrl = etServerUrl.text?.toString()?.trim() ?: ""

        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            putExtra(CaptureService.EXTRA_SERVER_URL, serverUrl)
        }

        startForegroundService(serviceIntent)
        updateStatusUI("Starting…", R.color.status_capturing)
    }

    // ─── UI Updates ───────────────────────────────────────────────

    private fun setCapturingState(capturing: Boolean) {
        isCapturing = capturing
        btnStart.isEnabled = !capturing
        btnStop.isEnabled = capturing
        etServerUrl.isEnabled = !capturing
    }

    private fun updateStatusUI(status: String, colorResId: Int) {
        tvStatus.text = status
        val color = ContextCompat.getColor(this, colorResId)
        tvStatus.setTextColor(color)

        // Update the dot indicator color
        val dot = statusIndicator.background
        if (dot is android.graphics.drawable.GradientDrawable) {
            dot.setColor(color)
        }
    }

    private fun showResult(prediction: String, confidence: Float) {
        val isReal = prediction.equals("Real", ignoreCase = true)
        val color = if (isReal) R.color.result_real else R.color.result_fake
        val emoji = if (isReal) "✅" else "⚠️"

        updateStatusUI("Result", ContextCompat.getColor(this, color).let {
            if (isReal) R.color.result_real else R.color.result_fake
        })

        resultCard.visibility = View.VISIBLE
        tvResult.text = "$emoji $prediction"
        tvResult.setTextColor(ContextCompat.getColor(this, color))
        tvConfidence.text = "Confidence: ${"%.1f".format(confidence * 100)}%"
    }
}
