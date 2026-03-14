package com.deepfake.capture

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating overlay with two states:
 *   • Collapsed: small draggable bubble icon
 *   • Expanded: panel with status, results, server URL, start/stop controls
 */
class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayManager"
        private const val PREFS_NAME = "deepfake_prefs"
        private const val KEY_SERVER_URL = "server_url"
    }

    private var windowManager: WindowManager? = null

    // Collapsed bubble
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // Expanded panel
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    // Panel UI references
    private var statusText: TextView? = null
    private var resultText: TextView? = null
    private var confidenceText: TextView? = null
    private var serverUrlInput: EditText? = null
    private var startBtn: TextView? = null
    private var stopBtn: TextView? = null
    private var statusDot: View? = null

    private var isExpanded = false
    private var isCapturing = false

    var onStartCapture: ((serverUrl: String) -> Unit)? = null
    var onStopCapture: (() -> Unit)? = null

    // ─── dp/sp helper ──────────────────────────────────────────
    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)

    // ─── Public API ────────────────────────────────────────────

    fun show() {
        if (bubbleView != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showBubble()
        Log.i(TAG, "Overlay shown (bubble)")
    }

    fun hide() {
        removeBubble()
        removePanel()
        windowManager = null
        Log.i(TAG, "Overlay hidden")
    }

    fun updateStatus(status: String) {
        panelView?.post {
            statusText?.text = status
            resultText?.text = ""
            confidenceText?.text = ""
            statusDot?.background = makeDot(Color.parseColor("#4FC3F7"))
        }
        // Also update bubble tint
        bubbleView?.post {
            (bubbleView as? TextView)?.setTextColor(Color.parseColor("#4FC3F7"))
        }
    }

    fun updateResult(prediction: String, confidence: Float) {
        val isReal = prediction.equals("Real", ignoreCase = true)
        val color = if (isReal) Color.parseColor("#66BB6A") else Color.parseColor("#EF5350")
        val emoji = if (isReal) "✅" else "⚠️"

        panelView?.post {
            statusText?.text = if (isReal) "Real Detected" else "Fake Detected"
            statusText?.setTextColor(color)
            resultText?.text = "$emoji $prediction"
            resultText?.setTextColor(color)
            confidenceText?.text = "Confidence: ${"%.1f".format(confidence * 100)}%"
            statusDot?.background = makeDot(color)

            // Subtle panel background tint
            panelView?.background = makeRoundedRect(
                if (isReal) "#0A66BB6A" else "#0AEF5350", 20
            )
        }

        // Update bubble color
        bubbleView?.post {
            (bubbleView as? TextView)?.apply {
                text = emoji
                setTextColor(color)
            }
        }
    }

    fun setCapturingState(capturing: Boolean) {
        isCapturing = capturing
        panelView?.post {
            startBtn?.apply {
                isEnabled = !capturing
                alpha = if (capturing) 0.4f else 1.0f
            }
            stopBtn?.apply {
                isEnabled = capturing
                alpha = if (capturing) 1.0f else 0.4f
            }
            serverUrlInput?.isEnabled = !capturing
        }
    }

    // ─── Collapsed Bubble ──────────────────────────────────────

    private fun showBubble() {
        val bubble = TextView(context).apply {
            text = "🛡️"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = makeRoundedRect("#E6181825", 50)
            elevation = dp(8).toFloat()
        }

        bubbleParams = WindowManager.LayoutParams(
            dp(52), dp(52),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(120)
        }

        // Drag + tap detection
        var startX = 0
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var moved = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams!!.x
                    startY = bubbleParams!!.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) moved = true
                    bubbleParams!!.x = startX + dx
                    bubbleParams!!.y = startY + dy
                    windowManager?.updateViewLayout(bubble, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleExpand()
                    true
                }
                else -> false
            }
        }

        bubbleView = bubble
        windowManager?.addView(bubble, bubbleParams)
    }

    private fun removeBubble() {
        bubbleView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        bubbleView = null
    }

    // ─── Expanded Panel ────────────────────────────────────────

    private fun toggleExpand() {
        if (isExpanded) {
            removePanel()
            isExpanded = false
        } else {
            showPanel()
            isExpanded = true
        }
    }

    private fun showPanel() {
        val panel = buildPanelLayout()

        panelParams = WindowManager.LayoutParams(
            dp(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleParams?.x ?: dp(8)) + dp(56)
            y = bubbleParams?.y ?: dp(120)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        panelView = panel
        windowManager?.addView(panel, panelParams)

        // Refresh button state
        setCapturingState(isCapturing)
    }

    private fun removePanel() {
        panelView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        panelView = null
        statusText = null
        resultText = null
        confidenceText = null
        serverUrlInput = null
        startBtn = null
        stopBtn = null
        statusDot = null
    }

    private fun buildPanelLayout(): LinearLayout {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_SERVER_URL, "http://10.0.2.2:7860") ?: ""

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = makeRoundedRect("#E6181825", 20)
            elevation = dp(12).toFloat()
        }

        // ── Header row: title + close button
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(context).apply {
            text = "🛡️ Deepfake Detector"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { toggleExpand() }
        }
        header.addView(closeBtn)
        root.addView(header)

        // ── Divider
        root.addView(makeDivider())

        // ── Status row
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
        }

        statusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(8)
            }
            background = makeDot(Color.parseColor("#78909C"))
        }
        statusRow.addView(statusDot)

        statusText = TextView(context).apply {
            text = if (isCapturing) "Capturing…" else "Idle"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 13f
        }
        statusRow.addView(statusText)
        root.addView(statusRow)

        // ── Result + confidence
        resultText = TextView(context).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, 0)
        }
        root.addView(resultText)

        confidenceText = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 12f
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(confidenceText)

        // ── Divider
        root.addView(makeDivider())

        // ── Server URL
        val urlLabel = TextView(context).apply {
            text = "Server URL"
            setTextColor(Color.parseColor("#60FFFFFF"))
            textSize = 11f
            setPadding(0, dp(6), 0, dp(2))
        }
        root.addView(urlLabel)

        serverUrlInput = EditText(context).apply {
            setText(savedUrl)
            setTextColor(Color.WHITE)
            textSize = 12f
            setHintTextColor(Color.parseColor("#40FFFFFF"))
            hint = "http://10.0.2.2:7860"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = makeRoundedRect("#20FFFFFF", 8)
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(serverUrlInput)

        // ── Buttons row
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER
        }

        startBtn = TextView(context).apply {
            text = "▶  Start"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = makeRoundedRect("#4CAF50", 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                val url = serverUrlInput?.text?.toString()?.trim() ?: ""
                if (url.isNotBlank()) {
                    // Save URL
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_SERVER_URL, url).apply()
                    onStartCapture?.invoke(url)
                }
            }
        }
        btnRow.addView(startBtn)

        stopBtn = TextView(context).apply {
            text = "⏹  Stop"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = makeRoundedRect("#EF5350", 12)
            alpha = 0.4f
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                onStopCapture?.invoke()
            }
        }
        btnRow.addView(stopBtn)
        root.addView(btnRow)

        return root
    }

    // ─── Drawable Helpers ──────────────────────────────────────

    private fun makeRoundedRect(colorHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(colorHex))
            setStroke(1, Color.parseColor("#20FFFFFF"))
        }
    }

    private fun makeDot(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(dp(8), dp(8))
        }
    }

    private fun makeDivider(): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(2)
            }
            setBackgroundColor(Color.parseColor("#15FFFFFF"))
        }
    }
}
