package com.vanta.ffaimbot

import android.app.Activity
import android.content.Intent
import android.graphics.PointF
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

    private var launched = false
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
            Logger.log("Main", "Shizuku permission granted")
            status("Shizuku OK")
            requestCapture()
        } else {
            Logger.log("Main", "Shizuku permission denied")
            status("Shizuku denied — open Shizuku app and grant permission")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Logger.init(this)
        Logger.log("Main", "onCreate")

        tvStatus       = findViewById(R.id.tvStatus)
        tvLog          = findViewById(R.id.tvLog)
        scrollLog      = findViewById(R.id.scrollLog)
        btnAction      = findViewById(R.id.btnAction)
        btnTestInject  = findViewById(R.id.btnTestInject)
        btnClearLog    = findViewById(R.id.btnClearLog)
        projMgr        = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnAction.setOnClickListener { flow() }

        btnClearLog.setOnClickListener {
            Logger.clear(this)
            tvLog.text = ""
        }

        // Fires a swipe at screen center to verify injection works without opening FF
        btnTestInject.setOnClickListener {
            Logger.log("Main", "Test inject triggered")
            Thread {
                val ok = InputInjector.init()
                Logger.log("Main", "Injector init = $ok")
                if (ok) {
                    val w = resources.displayMetrics.widthPixels.toFloat()
                    val h = resources.displayMetrics.heightPixels.toFloat()
                    val from = PointF(w / 2f, h / 2f)
                    val to   = PointF(w / 2f + 200f, h / 2f)
                    InputInjector.swipeAim(from, to, 100L)
                    Logger.log("Main", "Test swipe injected: ${from.x},${from.y} -> ${to.x},${to.y}")
                } else {
                    Logger.log("Main", "Injector init FAILED — check Shizuku")
                }
            }.start()
        }

        handler.post(logRefresher)
        flow()
    }

    private fun flow() {
        Logger.log("Main", "flow() — shizuku=${ShizukuHelper.isRunning()} perm=${ShizukuHelper.hasPermission()} proj=${ProjectionHolder.projection != null} overlay=${Settings.canDrawOverlays(this)}")
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
        Logger.log("Main", "requesting screen capture")
        startActivityForResult(projMgr.createScreenCaptureIntent(), RC_PROJ)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_PROJ) return

        Logger.log("Main", "onActivityResult resultCode=$resultCode dataNull=${data == null}")

        if (resultCode == Activity.RESULT_OK && data != null) {
            val projection = try {
                projMgr.getMediaProjection(resultCode, data)
            } catch (e: Exception) {
                Logger.log("Main", "getMediaProjection exception: ${e.message}")
                status("MediaProjection failed: ${e.message}")
                return
            }

            if (projection == null) {
                Logger.log("Main", "getMediaProjection returned null")
                status("Screen capture returned null — try again")
                return
            }

            Logger.log("Main", "MediaProjection created OK")
            ProjectionHolder.projection = projection
            flow()
        } else {
            Logger.log("Main", "screen capture denied or cancelled")
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
        Logger.log("Main", "starting AimbotService")
        startForegroundService(Intent(this, AimbotService::class.java))
        status("Running — open FF Max now")
        btnAction.text = "RUNNING"
        btnAction.isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        if (!launched && ProjectionHolder.projection != null && Settings.canDrawOverlays(this)) {
            launchService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(logRefresher)
        ShizukuHelper.removePermissionListener(shizukuListener)
    }

    private fun status(msg: String) {
        tvStatus.text = msg
    }

    companion object {
        private const val RC_PROJ = 2001
    }
}
