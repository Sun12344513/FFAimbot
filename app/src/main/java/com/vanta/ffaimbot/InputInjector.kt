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

    fun init(): Boolean {
        if (ready) return true

        // Path A — IInputManager AIDL via ShizukuBinderWrapper
        // Source: GameMapperMind/wiki/Injection-Architecture
        return try {
            Logger.log("Injector", "init: getting 'input' binder via Shizuku")
            val binder = ShizukuHelper.getSystemServiceBinder("input")
            if (binder == null) {
                Logger.log("Injector", "FAIL: getSystemServiceBinder returned null")
                return false
            }
            Logger.log("Injector", "binder OK: ${binder.javaClass.name}")

            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asIface = stub.getMethod("asInterface", android.os.IBinder::class.java)
            iim = asIface.invoke(null, binder)
            Logger.log("Injector", "IInputManager proxy: ${iim?.javaClass?.name}")

            // Try to find injectInputEvent — signature varies by API
            var m: Method? = null
            for (method in iim!!.javaClass.methods) {
                if (method.name == "injectInputEvent") {
                    Logger.log("Injector", "found method: ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
                    if (method.parameterTypes.size == 2) {
                        m = method
                        break
                    }
                }
            }
            if (m == null) {
                Logger.log("Injector", "FAIL: injectInputEvent(2 params) not found")
                return false
            }
            m.isAccessible = true
            injectMethod = m
            ready = true
            Logger.log("Injector", "init SUCCESS")
            true
        } catch (e: Exception) {
            Logger.log("Injector", "init EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun inject(event: MotionEvent): Boolean {
        return try {
            val result = injectMethod?.invoke(iim, event, 0) as? Boolean ?: false
            if (!result) Logger.log("Injector", "inject returned false")
            result
        } catch (e: Exception) {
            Logger.log("Injector", "inject EXCEPTION: ${e.message}")
            false
        }
    }

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
        action: Int, x: Float, y: Float,
        downTime: Long, eventTime: Long, source: Int
    ): MotionEvent {
        val pp = MotionEvent.PointerProperties().also {
            it.id = 0
            it.toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val pc = MotionEvent.PointerCoords().also {
            it.x = x; it.y = y
            it.pressure = 1f; it.size = 1f
        }
        return MotionEvent.obtain(
            downTime, eventTime, action,
            1, arrayOf(pp), arrayOf(pc),
            0, 0, 1f, 1f, -1, 0, source, 0
        )
    }
}
