package com.deepfake.capture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Minimal Activity — requests all required permissions, starts CaptureService,
 * then finishes itself so the user returns to the home screen with the overlay.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "deepfake_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_SERVER_URL = "http://10.0.2.2:7860"
        private const val OVERLAY_PERMISSION_REQUEST = 1234
    }

    private lateinit var tvPermissionStatus: TextView
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)

        // Setup MediaProjection result launcher
        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                tvPermissionStatus.text = "Starting service…"
                startCaptureService(result.resultCode, result.data!!)
            } else {
                tvPermissionStatus.text = "Screen capture denied. Tap to retry."
                tvPermissionStatus.setOnClickListener { startPermissionFlow() }
            }
        }

        // Setup runtime permission launcher
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                checkOverlayPermission()
            } else {
                tvPermissionStatus.text = "Permissions required. Tap to retry."
                tvPermissionStatus.setOnClickListener { startPermissionFlow() }
            }
        }

        // Start the permission flow automatically
        startPermissionFlow()
    }

    override fun onResume() {
        super.onResume()
        // Check if we're returning from overlay permission settings
        if (Settings.canDrawOverlays(this) && waitingForOverlay) {
            waitingForOverlay = false
            requestScreenCapture()
        }
    }

    private var waitingForOverlay = false

    // ─── Permission Flow ───────────────────────────────────────────

    private fun startPermissionFlow() {
        tvPermissionStatus.text = getString(R.string.permission_subtitle)
        tvPermissionStatus.setOnClickListener(null)

        // Step 1: Check runtime permissions
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
            tvPermissionStatus.text = "Requesting audio & notification permissions…"
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            checkOverlayPermission()
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            tvPermissionStatus.text = "Requesting overlay permission…"
            waitingForOverlay = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        tvPermissionStatus.text = "Requesting screen capture…"
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    // ─── Start Service & Finish ────────────────────────────────────

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            putExtra(CaptureService.EXTRA_SERVER_URL, serverUrl)
        }

        startForegroundService(serviceIntent)
        Toast.makeText(this, "Deepfake Detector started", Toast.LENGTH_SHORT).show()

        // Finish the Activity — overlay takes over from here
        finish()
    }
}
