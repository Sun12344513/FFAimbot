package com.vanta.ffaimbot

import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import java.lang.reflect.Method

object InputInjector {

    private var iim: Any? = null
    private var injectMethod: Method? = null
    private var ready = false

    // Call once after Shizuku permission is granted.
    // Pattern sourced from GameMapperMind/wiki/Injection-Architecture (Path A)
    fun init(): Boolean {
        if (ready) return true
        return try {
            val binder = ShizukuHelper.getSystemServiceBinder("input") ?: return false
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asIface = stub.getMethod("asInterface", android.os.IBinder::class.java)
            iim = asIface.invoke(null, binder)
            val m = iim!!.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.java
            )
            m.isAccessible = true
            injectMethod = m
            ready = true
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun inject(event: MotionEvent): Boolean {
        return try {
            // mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            injectMethod?.invoke(iim, event, 0) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    // Swipe to aim: simulates camera drag on the right side of the FF screen.
    // 3-step MOVE interpolation avoids velocity filter rejection.
    fun swipeAim(from: PointF, to: PointF, durationMs: Long) {
        val t0 = SystemClock.uptimeMillis()
        val src = InputDevice.SOURCE_TOUCHSCREEN

        buildEvent(MotionEvent.ACTION_DOWN, from.x, from.y, t0, t0, src).also {
            inject(it); it.recycle()
        }

        for (step in 1..3) {
            val frac = step.toFloat() / 3f
            val mx = from.x + (to.x - from.x) * frac
            val my = from.y + (to.y - from.y) * frac
            val mt = t0 + durationMs * step / 3
            buildEvent(MotionEvent.ACTION_MOVE, mx, my, t0, mt, src).also {
                inject(it); it.recycle()
            }
        }

        buildEvent(MotionEvent.ACTION_UP, to.x, to.y, t0, t0 + durationMs, src).also {
            inject(it); it.recycle()
        }
    }

    // Re-inject the fire tap that the proxy overlay consumed,
    // so FF still receives the shot input.
    fun reInjectTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val src = InputDevice.SOURCE_TOUCHSCREEN
        buildEvent(MotionEvent.ACTION_DOWN, x, y, now, now, src).also {
            inject(it); it.recycle()
        }
        buildEvent(MotionEvent.ACTION_UP, x, y, now, now + 50L, src).also {
            inject(it); it.recycle()
        }
    }

    private fun buildEvent(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
        source: Int
    ): MotionEvent {
        val pp = MotionEvent.PointerProperties().also {
            it.id = 0
            it.toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val pc = MotionEvent.PointerCoords().also {
            it.x = x
            it.y = y
            it.pressure = 1f
            it.size = 1f
        }
        return MotionEvent.obtain(
            downTime, eventTime, action,
            1, arrayOf(pp), arrayOf(pc),
            0, 0, 1f, 1f, -1, 0, source, 0
        )
    }
}
