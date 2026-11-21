package com.vanta.ffaimbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnAction: Button
    private lateinit var projMgr: MediaProjectionManager

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
                    status("Failed: ${e.message}")
                }
            }
            ProjectionHolder.projection == null -> {
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
            else -> launchService()
        }
    }

    @Suppress("DEPRECATION")
    private fun requestCapture() {
        startActivityForResult(projMgr.createScreenCaptureIntent(), RC_PROJ)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_PROJ) return

        if (resultCode == Activity.RESULT_OK && data != null) {
            val projection = try {
                projMgr.getMediaProjection(resultCode, data)
            } catch (e: Exception) {
                status("MediaProjection failed: ${e.message}")
                return
            }

            if (projection == null) {
                status("Screen capture returned null — try again")
                return
            }

            ProjectionHolder.projection = projection
            flow()
        } else {
            status("Screen capture denied — try again")
        }
    }

    private fun launchService() {
        if (launched) return
        if (!Settings.canDrawOverlays(this)) {
            status("Overlay permission needed")
            return
        }
        launched = true
        startForegroundService(Intent(this, AimbotService::class.java))
        status("Running — open FF Max now")
        btnAction.text = "RUNNING"
        btnAction.isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        if (!launched
            && ProjectionHolder.projection != null
            && Settings.canDrawOverlays(this)
        ) {
            launchService()
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
