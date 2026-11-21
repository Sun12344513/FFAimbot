package com.vanta.ffaimbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnAction: Button
    private lateinit var btnTestInject: Button
    private lateinit var btnClearLog: Button
    private lateinit var projMgr: MediaProjectionManager

    private val handler = Handler(Looper.getMainLooper())

    private val logRefresher = object : Runnable {
        override fun run() {
            tvLog.text = Logger.getAll()
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
            handler.postDelayed(this, 1000L)
        }
    }

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Logger.log("Main", "Shizuku granted")
            status("Shizuku OK")
            flow()
        } else {
            Logger.log("Main", "Shizuku denied")
            status("Shizuku denied — open Shizuku app and grant permission")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Logger.init(this)
        Logger.log("Main", "onCreate Android API=${android.os.Build.VERSION.SDK_INT}")

        tvStatus      = findViewById(R.id.tvStatus)
        tvLog         = findViewById(R.id.tvLog)
        scrollLog     = findViewById(R.id.scrollLog)
        btnAction     = findViewById(R.id.btnAction)
        btnTestInject = findViewById(R.id.btnTestInject)
        btnClearLog   = findViewById(R.id.btnClearLog)
        projMgr       = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnAction.setOnClickListener { flow() }

        btnClearLog.setOnClickListener {
            Logger.clear(this)
            tvLog.text = ""
        }

        btnTestInject.setOnClickListener {
            Logger.log("Main", "test inject pressed")
            Thread {
                val ok = InputInjector.init()
                Logger.log("Main", "injector init=$ok")
                if (ok) {
                    val w = resources.displayMetrics.widthPixels.toFloat()
                    val h = resources.displayMetrics.heightPixels.toFloat()
                    InputInjector.swipeAim(
                        android.graphics.PointF(w / 2f, h / 2f),
                        android.graphics.PointF(w / 2f + 300f, h / 2f),
                        100L
                    )
                    Logger.log("Main", "test swipe sent")
                }
            }.start()
        }

        handler.post(logRefresher)
        flow()
    }

    private fun flow() {
        Logger.log("Main", "flow() shizuku=${ShizukuHelper.isRunning()} perm=${ShizukuHelper.hasPermission()} overlay=${Settings.canDrawOverlays(this)}")
        when {
            !ShizukuHelper.isRunning() -> {
                status("Shizuku not running — start Shizuku first")
            }
            !ShizukuHelper.hasPermission() -> {
                status("Requesting Shizuku permission…")
                try {
                    ShizukuHelper.requestPermission(shizukuListener)
                } catch (e: Exception) {
                    Logger.log("Main", "requestPermission failed: ${e.message}")
                    status("Shizuku error: ${e.message}")
                }
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
            else -> {
                // Android 14+: foreground service with mediaProjection type must be running
                // BEFORE getMediaProjection() is called.
                // Step 1: start service (foreground only, no projection yet)
                // Step 2: request screen capture
                // Step 3: onActivityResult sends result to already-running service
                // Step 4: service calls getMediaProjection() — now valid
                Logger.log("Main", "starting service foreground-only, then requesting capture")
                status("Starting service…")

                val fgIntent = Intent(this, AimbotService::class.java).apply {
                    action = AimbotService.ACTION_START_FG
                }
                startForegroundService(fgIntent)

                // Small delay to let service enter foreground before system validates the token
                handler.postDelayed({ requestCapture() }, 500L)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestCapture() {
        Logger.log("Main", "requesting screen capture")
        status("Grant screen capture…")
        startActivityForResult(projMgr.createScreenCaptureIntent(), RC_PROJ)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_PROJ) return

        Logger.log("Main", "onActivityResult resultCode=$resultCode dataNull=${data == null}")

        if (resultCode == Activity.RESULT_OK && data != null) {
            // Send result to the already-running foreground service.
            // Service calls getMediaProjection() — valid because service is already foreground.
            Logger.log("Main", "sending projection data to service")
            val svcIntent = Intent(this, AimbotService::class.java).apply {
                action = AimbotService.ACTION_ATTACH_PROJECTION
                putExtra(AimbotService.EXTRA_RESULT_CODE, resultCode)
                putExtra(AimbotService.EXTRA_DATA, data)
            }
            startService(svcIntent)

            status("Running — open FF Max now")
            btnAction.text = "RUNNING"
            btnAction.isEnabled = false
        } else {
            Logger.log("Main", "screen capture denied resultCode=$resultCode")
            status("Screen capture denied — tap START to retry")
            btnAction.isEnabled = true
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check overlay after returning from settings
        if (ShizukuHelper.isRunning() && ShizukuHelper.hasPermission()
            && Settings.canDrawOverlays(this)
            && btnAction.isEnabled
        ) {
            // Don't auto-restart, let user tap START
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(logRefresher)
        ShizukuHelper.removePermissionListener(shizukuListener)
    }

    private fun status(msg: String) { tvStatus.text = msg }

    companion object { private const val RC_PROJ = 2001 }
}
