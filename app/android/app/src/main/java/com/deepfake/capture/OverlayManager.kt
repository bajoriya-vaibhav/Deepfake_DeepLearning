package com.deepfake.capture

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Manages an optional floating overlay that shows the deepfake prediction result
 * without interrupting video playback. The overlay is a small draggable pill.
 */
class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayManager"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var resultTextView: TextView? = null
    private var confidenceTextView: TextView? = null

    fun show() {
        if (overlayView != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 16)
            background = createRoundedBackground()
        }

        resultTextView = TextView(context).apply {
            text = "Monitoring…"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        confidenceTextView = TextView(context).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#B0FFFFFF"))
            gravity = Gravity.CENTER
        }

        layout.addView(resultTextView)
        layout.addView(confidenceTextView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 100
        }

        // Make it draggable
        makeDraggable(layout, params)

        overlayView = layout
        windowManager?.addView(layout, params)
        Log.i(TAG, "Overlay shown")
    }

    fun updateResult(prediction: String, confidence: Float) {
        overlayView?.post {
            val isReal = prediction.equals("Real", ignoreCase = true)
            val emoji = if (isReal) "✅" else "⚠️"
            val color = if (isReal) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")

            resultTextView?.text = "$emoji $prediction"
            resultTextView?.setTextColor(color)
            confidenceTextView?.text = "Confidence: ${"%.1f".format(confidence * 100)}%"

            // Pulse the background color
            overlayView?.background = createRoundedBackground(
                if (isReal) "#1A4CAF50" else "#1AF44336"
            )
        }
    }

    fun updateStatus(status: String) {
        overlayView?.post {
            resultTextView?.text = status
            resultTextView?.setTextColor(Color.WHITE)
            confidenceTextView?.text = ""
            overlayView?.background = createRoundedBackground()
        }
    }

    fun hide() {
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
        resultTextView = null
        confidenceTextView = null
        Log.i(TAG, "Overlay hidden")
    }

    private fun createRoundedBackground(tintColor: String? = null): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(Color.parseColor(tintColor ?: "#CC1A1A2E"))
            setStroke(2, Color.parseColor("#40FFFFFF"))
        }
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }
}
