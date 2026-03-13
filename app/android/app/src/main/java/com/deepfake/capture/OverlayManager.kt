package com.deepfake.capture

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Manages a compact, notification-style floating overlay with:
 * - Header: shield icon + title + close button
 * - Status: bullet + status text
 * - Result: colored prediction + confidence
 * - Server URL: editable text field
 * - Buttons: Start + Stop side by side
 */
class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayManager"
        private const val PREFS_NAME = "deepfake_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_SERVER_URL = "http://10.0.2.2:7860"
    }

    /** Callback interface for overlay button actions */
    interface OverlayCallback {
        fun onStartCapture(serverUrl: String)
        fun onStopCapture()
        fun onCloseOverlay()
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var statusTextView: TextView? = null
    private var resultTextView: TextView? = null
    private var confidenceTextView: TextView? = null
    private var serverUrlInput: EditText? = null
    private var btnStart: TextView? = null
    private var btnStop: TextView? = null
    private var statusDot: View? = null
    private var callback: OverlayCallback? = null
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setCallback(cb: OverlayCallback) {
        callback = cb
    }

    fun show() {
        if (overlayView != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        // ─── Root container ────────────────────────────────────────
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = createOverlayBackground()
            elevation = dp(8).toFloat()
        }

        // ─── Header row: icon + title + close ──────────────────────
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val iconText = TextView(context).apply {
            text = "🛡️"
            textSize = 18f
        }

        val titleText = TextView(context).apply {
            text = "Deepfake Detector"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#B0FFFFFF"))
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { callback?.onCloseOverlay() }
        }

        header.addView(iconText)
        header.addView(titleText)
        header.addView(closeBtn)
        root.addView(header)

        // ─── Status row: dot + status ──────────────────────────────
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }

        statusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#9E9E9E"))
            }
        }

        statusTextView = TextView(context).apply {
            text = "Idle"
            textSize = 13f
            setTextColor(Color.parseColor("#B0FFFFFF"))
        }

        statusRow.addView(statusDot)
        statusRow.addView(statusTextView)
        root.addView(statusRow)

        // ─── Result area: prediction + confidence ──────────────────
        resultTextView = TextView(context).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        confidenceTextView = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#B0FFFFFF"))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }

        root.addView(resultTextView)
        root.addView(confidenceTextView)

        // ─── Server URL label + input ──────────────────────────────
        val urlLabel = TextView(context).apply {
            text = "Server URL:"
            textSize = 11f
            setTextColor(Color.parseColor("#80FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        serverUrlInput = EditText(context).apply {
            val savedUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
            setText(savedUrl)
            textSize = 13f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#60FFFFFF"))
            hint = "http://10.0.2.2:7860"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#20FFFFFF"))
                setStroke(1, Color.parseColor("#40FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        root.addView(urlLabel)
        root.addView(serverUrlInput)

        // ─── Button row: Start + Stop ──────────────────────────────
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        btnStart = createButton("▶ Start", "#2E7D32", "#1A4CAF50").apply {
            setOnClickListener {
                val url = serverUrlInput?.text?.toString()?.trim() ?: DEFAULT_SERVER_URL
                prefs.edit().putString(KEY_SERVER_URL, url).apply()
                callback?.onStartCapture(url)
            }
        }

        btnStop = createButton("⏹ Stop", "#C62828", "#1AF44336").apply {
            setOnClickListener { callback?.onStopCapture() }
            alpha = 0.5f
            isEnabled = false
        }

        buttonRow.addView(btnStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        })
        buttonRow.addView(btnStop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        root.addView(buttonRow)

        // ─── Window params ─────────────────────────────────────────
        val width = (context.resources.displayMetrics.widthPixels * 0.80).toInt()

        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        makeDraggable(root, params)

        overlayView = root
        windowManager?.addView(root, params)
        Log.i(TAG, "Overlay shown")
    }

    // ─── Public update methods ─────────────────────────────────────

    fun updateStatus(status: String) {
        overlayView?.post {
            statusTextView?.text = status

            val dotColor = when {
                status.contains("Captur", ignoreCase = true) -> "#2196F3"
                status.contains("Analyz", ignoreCase = true) -> "#FFC107"
                status.contains("Error", ignoreCase = true) -> "#F44336"
                else -> "#9E9E9E"
            }
            val dotBg = statusDot?.background
            if (dotBg is GradientDrawable) {
                dotBg.setColor(Color.parseColor(dotColor))
            }
        }
    }

    fun updateResult(prediction: String, confidence: Float) {
        overlayView?.post {
            val isReal = prediction.equals("Real", ignoreCase = true)
            val color = if (isReal) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
            val emoji = if (isReal) "✅" else "⚠️"

            resultTextView?.apply {
                text = "$emoji $prediction"
                setTextColor(color)
                visibility = View.VISIBLE
            }
            confidenceTextView?.apply {
                text = "Confidence: ${"%.1f".format(confidence * 100)}%"
                visibility = View.VISIBLE
            }

            // Update status to show result type
            val statusText = if (isReal) "Real Detected" else "Fake Detected"
            statusTextView?.text = statusText
            statusTextView?.setTextColor(color)

            val dotBg = statusDot?.background
            if (dotBg is GradientDrawable) {
                dotBg.setColor(color)
            }
        }
    }

    fun setCapturingState(capturing: Boolean) {
        overlayView?.post {
            btnStart?.apply {
                isEnabled = !capturing
                alpha = if (capturing) 0.5f else 1.0f
            }
            btnStop?.apply {
                isEnabled = capturing
                alpha = if (capturing) 1.0f else 0.5f
            }
            serverUrlInput?.isEnabled = !capturing

            if (!capturing) {
                resultTextView?.visibility = View.GONE
                confidenceTextView?.visibility = View.GONE
                statusTextView?.text = "Idle"
                statusTextView?.setTextColor(Color.parseColor("#B0FFFFFF"))
                val dotBg = statusDot?.background
                if (dotBg is GradientDrawable) {
                    dotBg.setColor(Color.parseColor("#9E9E9E"))
                }
            }
        }
    }

    fun hide() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
            overlayView = null
        }
        statusTextView = null
        resultTextView = null
        confidenceTextView = null
        serverUrlInput = null
        btnStart = null
        btnStop = null
        statusDot = null
        Log.i(TAG, "Overlay hidden")
    }

    // ─── Private helpers ───────────────────────────────────────────

    private fun createOverlayBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f,
                context.resources.displayMetrics
            )
            setColor(Color.parseColor("#E6202030"))
            setStroke(
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 1f,
                    context.resources.displayMetrics
                ).toInt(),
                Color.parseColor("#30FFFFFF")
            )
        }
    }

    private fun createButton(label: String, borderColor: String, bgColor: String): TextView {
        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        return TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor(bgColor))
                setStroke(dp(1), Color.parseColor(borderColor))
            }
        }
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Let child views handle the click
                        false
                    } else {
                        true
                    }
                }
                else -> false
            }
        }
    }
}
