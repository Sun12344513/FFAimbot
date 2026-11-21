package com.vanta.ffaimbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnAction: Button
    private lateinit var projMgr: MediaProjectionManager

    private var projCode = Activity.RESULT_CANCELED
    private var projData: Intent? = null
    private var launched = false

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            status("Shizuku OK")
            requestCapture()
        } else {
            status("Shizuku denied — open Shizuku app and grant permission")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus  = findViewById(R.id.tvStatus)
        btnAction = findViewById(R.id.btnAction)
        projMgr   = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnAction.setOnClickListener { flow() }
        flow()
    }

    private fun flow() {
        when {
            !ShizukuHelper.isRunning() -> {
                status("Shizuku not running — start Shizuku first")
            }
            !ShizukuHelper.hasPermission() -> {
                status("Requesting Shizuku permission…")
                try {
                    ShizukuHelper.requestPermission(shizukuListener)
                } catch (e: Exception) {
                    status("Failed to request Shizuku permission: ${e.message}")
                }
            }
            projData == null -> {
                status("Grant screen capture…")
                requestCapture()
            }
            !Settings.canDrawOverlays(this) -> {
                status("Grant overlay permission…")
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            else -> startService()
        }
    }

    @Suppress("DEPRECATION")
    private fun requestCapture() {
        startActivityForResult(projMgr.createScreenCaptureIntent(), RC_PROJ)
    }

    private fun startService() {
        if (launched) return
        if (!Settings.canDrawOverlays(this)) {
            status("Overlay permission needed")
            return
        }
        launched = true
        val svc = Intent(this, AimbotService::class.java).apply {
            putExtra(AimbotService.EXTRA_RESULT_CODE, projCode)
            putExtra(AimbotService.EXTRA_DATA, projData)
        }
        startForegroundService(svc)
        status("Running — open FF Max now")
        btnAction.text = "RUNNING"
        btnAction.isEnabled = false
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_PROJ) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                projCode = resultCode
                projData = data
                flow()
            } else {
                status("Screen capture denied")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!launched && projData != null && Settings.canDrawOverlays(this)) {
            startService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ShizukuHelper.removePermissionListener(shizukuListener)
    }

    private fun status(msg: String) {
        tvStatus.text = msg
    }

    companion object {
        private const val RC_PROJ = 2001
    }
}
