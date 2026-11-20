// language: Kotlin, file: ShizukuHelper.kt
// Manages Shizuku permission lifecycle and binder availability check
package com.vanta.ffaimbot

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuHelper {

    private const val REQUEST_CODE = 1001

    // true if Shizuku daemon is running and permission granted
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && hasPermission()
        } catch (e: IllegalStateException) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            false
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    }

    // Call from Activity.onRequestPermissionsResult with Shizuku's callback registration
    fun requestPermission(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    fun removePermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    // Wraps a system binder with Shizuku's proxy — allows hidden API calls at ADB level
    fun getSystemServiceBinder(serviceName: String): android.os.IBinder? {
        return try {
            // Reflect ServiceManager since it's hidden, then wrap with ShizukuBinderWrapper
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getService = serviceManagerClass.getMethod("getService", String::class.java)
            val rawBinder = getService.invoke(null, serviceName) as? android.os.IBinder
                ?: return null
            rikka.shizuku.ShizukuBinderWrapper(rawBinder)
        } catch (e: Exception) {
            null
        }
    }
}
