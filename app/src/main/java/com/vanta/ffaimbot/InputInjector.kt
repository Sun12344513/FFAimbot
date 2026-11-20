// language: Kotlin, file: InputInjector.kt
// Injects MotionEvents via IInputManager hidden API through Shizuku binder wrapper.
// This is the core proxy mechanism — FF sees injected events as real hardware input.
package com.vanta.ffaimbot

import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

object InputInjector {

    // Cached IInputManager reflection references — built once, reused every frame
    private var iInputManager: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null
    private var initialized = false

    fun init(): Boolean {
        if (initialized) return iInputManager != null
        initialized = true
        return try {
            val binder = ShizukuHelper.getSystemServiceBinder("input") ?: return false

            // Reflect IInputManager$Stub.asInterface — not in public SDK
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            iInputManager = asInterface.invoke(null, binder)

            // injectInputEvent(InputEvent event, int mode)
            // mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC — non-blocking, returns immediately
            injectMethod = iInputManager!!.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.java
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    // Inject a raw MotionEvent — used both for aim swipes and re-injecting intercepted fire taps
    fun inject(event: MotionEvent): Boolean {
        return try {
            injectMethod?.invoke(iInputManager, event, 0) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    // Swipe gesture from [from] to [to] over [durationMs] — simulates camera drag to snap aim
    // Keep durationMs ≥ 8ms; below that FF's input filter may discard the event
    fun swipeAim(from: PointF, to: PointF, durationMs: Long = 32L) {
        val downTime = SystemClock.uptimeMillis()
        val source = InputDevice.SOURCE_TOUCHSCREEN

        // ACTION_DOWN
        buildEvent(MotionEvent.ACTION_DOWN, from.x, from.y, downTime, downTime, source).let {
            inject(it); it.recycle()
        }

        // ACTION_MOVE — interpolate 3 steps to avoid velocity filter rejection
        val steps = 3
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val x = from.x + (to.x - from.x) * t
            val y = from.y + (to.y - from.y) * t
            val moveTime = downTime + (durationMs * i / steps)
            buildEvent(MotionEvent.ACTION_MOVE, x, y, downTime, moveTime, source).let {
                inject(it); it.recycle()
            }
        }

        // ACTION_UP
        val upTime = downTime + durationMs
        buildEvent(MotionEvent.ACTION_UP, to.x, to.y, downTime, upTime, source).let {
            inject(it); it.recycle()
        }
    }

    // Re-inject an intercepted touch so FF still receives its original fire tap
    fun reInjectTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val source = InputDevice.SOURCE_TOUCHSCREEN
        buildEvent(MotionEvent.ACTION_DOWN, x, y, now, now, source).let { inject(it); it.recycle() }
        buildEvent(MotionEvent.ACTION_UP, x, y, now, now + 50L, source).let { inject(it); it.recycle() }
    }

    private fun buildEvent(
        action: Int, x: Float, y: Float,
        downTime: Long, eventTime: Long,
        source: Int
    ): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            pressure = 1f; size = 1f
        }
        return MotionEvent.obtain(
            downTime, eventTime, action,
            1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f,
            -1, 0, source, 0
        )
    }
}
