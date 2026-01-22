package com.deepfake.detection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.deepfake.detection.service.DeepfakeService

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnOverlay: Button
    private lateinit var etUrl: EditText

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Permissions Granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permissions Required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btn_start_service)
        btnStop = findViewById(R.id.btn_stop_service)
        btnOverlay = findViewById(R.id.btn_overlay_perm)
        etUrl = findViewById(R.id.et_server_url)

        btnOverlay.setOnClickListener {
            checkOverlayPermission()
        }

        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant Overlay Permission first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestRuntimePermissions()
                return@setOnClickListener
            }
            
            val intent = Intent(this, DeepfakeService::class.java)
            intent.putExtra("URL", etUrl.text.toString())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            btnStart.setEnabled(false)
            btnStop.setEnabled(true)
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, DeepfakeService::class.java)
            stopService(intent)
            btnStart.setEnabled(true)
            btnStop.setEnabled(false)
        }
        
        requestRuntimePermissions()
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "Overlay already granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestRuntimePermissions() {
        val perms = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        permissionLauncher.launch(perms)
    }
}
