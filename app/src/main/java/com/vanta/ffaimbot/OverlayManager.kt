// language: Kotlin, file: OverlayManager.kt
// Places two overlays:
//   1. Control panel (top-left) — toggle aimbot on/off, show detection debug dot
//   2. Fire proxy zone (bottom-right) — transparent touch interceptor over FF's fire button
//      Intercepts ACTION_DOWN, triggers aim, then re-injects the tap so FF still fires.
package com.vanta.ffaimbot

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.PixelFormat
import android.view.*
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import com.vanta.ffaimbot.databinding.OverlayControlBinding

class OverlayManager(
    private val context: Context,
    private val onFireIntercepted: (x: Float, y: Float) -> Unit,
    private val onAimbotToggled: (enabled: Boolean) -> Unit
) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var controlView: View? = null
    private var fireProxyView: View? = null
    private var debugDot: View? = null

    private val screenWidth: Int
    private val screenHeight: Int

    init {
        val metrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    fun show() {
        addControlPanel()
        addFireProxy()
        addDebugDot()
    }

    fun hide() {
        controlView?.let { wm.removeView(it) }
        fireProxyView?.let { wm.removeView(it) }
        debugDot?.let { wm.removeView(it) }
        controlView = null; fireProxyView = null; debugDot = null
    }

    // Move the debug dot to show where the detector found the enemy
    fun updateDebugDot(point: PointF?) {
        val dot = debugDot ?: return
        val params = dot.layoutParams as WindowManager.LayoutParams
        if (point != null) {
            params.x = point.x.toInt() - 8
            params.y = point.y.toInt() - 8
            dot.visibility = View.VISIBLE
        } else {
            dot.visibility = View.INVISIBLE
        }
        wm.updateViewLayout(dot, params)
    }

    private fun addControlPanel() {
        val view = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding(16, 16, 16, 16)
        }

        val toggle = Switch(context).apply {
            text = "AIMBOT"
            setTextColor(Color.WHITE)
            isChecked = false
            setOnCheckedChangeListener { _, checked -> onAimbotToggled(checked) }
        }
        view.addView(toggle)

        val params = baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24; y = 120   // below status bar
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        wm.addView(view, params)
        controlView = view
    }

    private fun addFireProxy() {
        // FF fire button zone — right 42% of screen, bottom 38%
        // Tune these if the fire button position differs on dj's device/UI scale
        val proxyWidth = (screenWidth * 0.42f).toInt()
        val proxyHeight = (screenHeight * 0.38f).toInt()
        val proxyX = screenWidth - proxyWidth
        val proxyY = screenHeight - proxyHeight

        val view = object : View(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    // Translate local coordinates to screen coordinates
                    val screenX = event.rawX
                    val screenY = event.rawY
                    onFireIntercepted(screenX, screenY)
                    // Return true — consume the event; AimbotService will re-inject it
                }
                return true
            }
        }
        view.setBackgroundColor(Color.TRANSPARENT)

        val params = baseParams(proxyWidth, proxyHeight).apply {
            gravity = Gravity.TOP or Gravity.START
            x = proxyX; y = proxyY
            // NOT FLAG_NOT_TOUCHABLE — we need touches
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        wm.addView(view, params)
        fireProxyView = view
    }

    private fun addDebugDot() {
        val dot = View(context).apply {
            setBackgroundColor(Color.CYAN)
            visibility = View.INVISIBLE
        }
        val params = baseParams(16, 16).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        wm.addView(dot, params)
        debugDot = dot
    }

    private fun baseParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )
}
