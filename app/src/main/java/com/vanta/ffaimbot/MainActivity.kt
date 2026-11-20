// language: Kotlin, file: MainActivity.kt
// Permission flow: Shizuku → MediaProjection → SYSTEM_ALERT_WINDOW → start service
package com.vanta.ffaimbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var projMgr: MediaProjectionManager

    private var projectionResultCode = Activity.RESULT_CANCELED
    private var projectionData: Intent? = null

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            updateStatus("Shizuku ✓ — Request screen capture")
            requestScreenCapture()
        } else {
            updateStatus("Shizuku permission denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.tvStatus)
        actionButton = findViewById(R.id.btnAction)
        projMgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        actionButton.setOnClickListener { startPermissionFlow() }
        startPermissionFlow()
    }

    private fun startPermissionFlow() {
        when {
            !ShizukuHelper.isAvailable() && !ShizukuHelper.hasPermission() -> {
                updateStatus("Requesting Shizuku permission…")
                ShizukuHelper.requestPermission(shizukuPermissionListener)
            }
            ShizukuHelper.hasPermission() && projectionData == null -> {
                updateStatus("Shizuku ✓ — Request screen capture")
                requestScreenCapture()
            }
            !Settings.canDrawOverlays(this) -> {
                updateStatus("Grant overlay permission…")
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
            else -> launchService()
        }
    }

    private fun requestScreenCapture() {
        @Suppress("DEPRECATION")
        startActivityForResult(projMgr.createScreenCaptureIntent(), RC_PROJECTION)
    }

    private fun launchService() {
        if (!Settings.canDrawOverlays(this)) {
            updateStatus("Overlay permission required.")
            return
        }
        val intent = Intent(this, AimbotService::class.java).apply {
            putExtra(AimbotService.EXTRA_PROJECTION_RESULT, projectionResultCode)
            putExtra(AimbotService.EXTRA_PROJECTION_DATA, projectionData)
        }
        startForegroundService(intent)
        updateStatus("Service running. Open FF.")
        actionButton.text = "RUNNING"
        actionButton.isEnabled = false
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                projectionResultCode = resultCode
                projectionData = data
                updateStatus("Screen capture ✓ — Grant overlay")
                startPermissionFlow()
            } else {
                updateStatus("Screen capture denied.")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Check overlay after returning from settings
        if (Settings.canDrawOverlays(this) && projectionData != null) {
            launchService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ShizukuHelper.removePermissionListener(shizukuPermissionListener)
    }

    private fun updateStatus(msg: String) {
        statusText.text = msg
    }

    companion object {
        private const val RC_PROJECTION = 2001
    }
}
