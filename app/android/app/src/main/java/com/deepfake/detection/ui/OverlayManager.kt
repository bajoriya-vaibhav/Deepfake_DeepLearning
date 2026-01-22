package com.deepfake.detection.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.deepfake.detection.R

class OverlayManager(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var statusText: TextView? = null
    private var confidenceText: TextView? = null

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    fun showOverlay() {
        if (overlayView != null) return

        overlayView = LayoutInflater.from(context).inflate(R.layout.layout_overlay, null)
        statusText = overlayView!!.findViewById(R.id.tv_status)
        confidenceText = overlayView!!.findViewById(R.id.tv_confidence)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 20
        params.y = 200

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hideOverlay() {
        if (overlayView == null) return
        try {
            windowManager?.removeView(overlayView)
            overlayView = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateStatus(status: String, confidence: String, color: Int = Color.WHITE) {
        // Must run on UI thread if called from background
        statusText?.post {
            statusText?.text = status
            statusText?.setTextColor(color)
            confidenceText?.text = confidence
        }
    }
}
