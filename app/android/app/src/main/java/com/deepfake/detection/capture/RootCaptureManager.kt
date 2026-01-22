package com.deepfake.detection.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.DataOutputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream

object RootCaptureManager {
    
    // Video Capture using screencap
    fun captureScreenTrace(): Bitmap? {
        var process: Process? = null
        try {
            // "screencap -p" captures png to stdout
            process = Runtime.getRuntime().exec("su -c screencap -p")
            val inputStream: InputStream = process.inputStream
            val bitmap = BitmapFactory.decodeStream(inputStream)
            process.waitFor()
            return bitmap
        } catch (e: Exception) {
            Log.e("RootCapture", "Screen capture failed", e)
            return null
        } finally {
            process?.destroy()
        }
    }

    // Checking Root
    fun isRootAvailable(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("su -c id")
            val exitCode = p.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    // Mock for Testing
    fun generateMockTrace(): Bitmap {
        val width = 720
        val height = 1280
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        paint.color = android.graphics.Color.LTGRAY
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = android.graphics.Color.RED
        paint.textSize = 50f
        canvas.drawText("SIMULATION MODE", 100f, 600f, paint)
        canvas.drawText("Timestamp: ${System.currentTimeMillis()}", 100f, 700f, paint)
        return bitmap
    }
}
