package com.vanta.ffaimbot

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class OverlayManager(
    private val context: Context,
    private val onFireIntercepted: (x: Float, y: Float) -> Unit,
    private val onAimbotToggled: (enabled: Boolean) -> Unit
) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sw = context.resources.displayMetrics.widthPixels
    private val sh = context.resources.displayMetrics.heightPixels

    private var controlView: View? = null
    private var fireProxy: View? = null
    private var debugDot: View? = null

    fun show() {
        addControl()
        addFireProxy()
        addDebugDot()
    }

    fun hide() {
        for (v in listOf(controlView, fireProxy, debugDot)) {
            if (v != null) {
                try { wm.removeView(v) } catch (e: Exception) { /* ignore */ }
            }
        }
        controlView = null
        fireProxy = null
        debugDot = null
    }

    fun updateDebugDot(point: PointF?) {
        val dot = debugDot ?: return
        val lp = dot.layoutParams as WindowManager.LayoutParams
        if (point != null) {
            lp.x = point.x.toInt() - 8
            lp.y = point.y.toInt() - 8
            dot.visibility = View.VISIBLE
        } else {
            dot.visibility = View.INVISIBLE
        }
        try { wm.updateViewLayout(dot, lp) } catch (e: Exception) { /* ignore */ }
    }

    private fun addControl() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(20, 20, 20, 20)
        }

        root.addView(TextView(context).apply {
            text = "AIMBOT OB55"
            setTextColor(Color.WHITE)
            textSize = 11f
        })

        @Suppress("DEPRECATION")
        root.addView(Switch(context).apply {
            text = "Enable"
            setTextColor(Color.WHITE)
            isChecked = false
            setOnCheckedChangeListener { _, on -> onAimbotToggled(on) }
        })

        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 100
        }
        wm.addView(root, lp)
        controlView = root
    }

    private fun addFireProxy() {
        // Right 42%, bottom 38% of screen — covers FF's default fire button zone.
        // Adjust if using a custom HUD scale in FF settings.
        val w = (sw * 0.42f).toInt()
        val h = (sh * 0.38f).toInt()

        val proxy = object : View(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onFireIntercepted(event.rawX, event.rawY)
                }
                return true  // consume — service re-injects the original tap via Shizuku
            }
        }
        proxy.setBackgroundColor(Color.TRANSPARENT)

        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sw - w
            y = sh - h
        }
        wm.addView(proxy, lp)
        fireProxy = proxy
    }

    private fun addDebugDot() {
        val dot = View(context).apply {
            setBackgroundColor(Color.CYAN)
            visibility = View.INVISIBLE
        }
        val lp = overlayParams(16, 16).apply {
            gravity = Gravity.TOP or Gravity.START
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        wm.addView(dot, lp)
        debugDot = dot
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )
}
