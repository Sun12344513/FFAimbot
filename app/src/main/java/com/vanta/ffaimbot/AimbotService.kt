// language: Kotlin, file: AimbotService.kt
package com.vanta.ffaimbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PointF
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AimbotService : Service() {

    companion object {
        const val ACTION_START_FG          = "com.vanta.ffaimbot.START_FG"
        const val ACTION_ATTACH_PROJECTION = "com.vanta.ffaimbot.ATTACH_PROJECTION"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA        = "data"

        private const val CHANNEL_ID = "ffaimbot"
        private const val NOTIF_ID   = 1
        private const val FPS_DELAY  = 66L

        private fun aimMs() = 28L + (Math.random() * 16).toLong()
    }

    private var capture: ScreenCaptureManager? = null
    private var detector: EnemyDetector? = null
    private var overlay: OverlayManager? = null

    @Volatile private var enabled = false
    @Volatile private var lastTarget: PointF? = null

    private var frameCount  = 0
    private var detectCount = 0
    private var projectionAttached = false

    private val job         = SupervisorJob()
    private val scope       = CoroutineScope(Dispatchers.Default + job)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif("Waiting for screen capture…"))
        Logger.log("Service", "onCreate — foreground started, waiting for projection")
        InputInjector.init()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ""
        Logger.log("Service", "onStartCommand action=$action")

        when (action) {
            ACTION_START_FG -> {
                Logger.log("Service", "foreground ready, awaiting projection data")
            }

            ACTION_ATTACH_PROJECTION -> {
                if (projectionAttached) {
                    Logger.log("Service", "projection already attached, ignoring")
                    return START_STICKY
                }

                if (intent == null) {
                    Logger.log("Service", "FAIL: intent is null")
                    return START_STICKY
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                Logger.log("Service", "attaching projection resultCode=$resultCode dataNull=${data == null}")

                if (resultCode != android.app.Activity.RESULT_OK || data == null) {
                    Logger.log("Service", "FAIL: bad resultCode or null data")
                    return START_STICKY
                }

                val projMgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

                val projection: MediaProjection = try {
                    projMgr.getMediaProjection(resultCode, data)
                } catch (e: Exception) {
                    Logger.log("Service", "getMediaProjection EXCEPTION: ${e.message}")
                    return START_STICKY
                } ?: run {
                    Logger.log("Service", "getMediaProjection returned null")
                    return START_STICKY
                }

                Logger.log("Service", "MediaProjection obtained OK")
                projectionAttached = true
                updateNotif("Active")
                startCapture(projection)
            }

            else -> {
                Logger.log("Service", "unknown or null action")
            }
        }

        return START_STICKY
    }

    private fun startCapture(projection: MediaProjection) {
        Logger.log("Service", "starting capture")
        capture = ScreenCaptureManager(this, projection).also { it.start() }
        Logger.log("Service", "capture started ${capture!!.screenWidth}x${capture!!.screenHeight}")

        detector = EnemyDetector(capture!!.screenWidth, capture!!.screenHeight)
        overlay = OverlayManager(
            context           = this,
            onFireIntercepted = ::onFire,
            onAimbotToggled   = { on ->
                enabled = on
                Logger.log("Service", "aimbot enabled=$on")
            }
        )
        mainHandler.post { overlay?.show() }
        startScanLoop()
    }

    private fun onFire(fireX: Float, fireY: Float) {
        Logger.log("Service", "fire at $fireX,$fireY enabled=$enabled target=$lastTarget")
        if (enabled) {
            val target = lastTarget
            if (target != null) {
                val w  = capture?.screenWidth  ?: return
                val h  = capture?.screenHeight ?: return
                val cx = w / 2f
                val cy = h / 2f

                val dx = (target.x - cx).coerceIn(-400f, 400f)
                val dy = (target.y - cy).coerceIn(-400f, 400f)
                val jx = (Math.random() * 40 - 20).toFloat()
                val jy = (Math.random() * 40 - 20).toFloat()
                val from = PointF(cx + jx, cy + jy)
                val to   = PointF(from.x + dx * 0.6f, from.y + dy * 0.6f)

                Logger.log("Service", "swipe (${from.x.toInt()},${from.y.toInt()}) -> (${to.x.toInt()},${to.y.toInt()})")
                InputInjector.swipeAim(from, to, aimMs())
            } else {
                Logger.log("Service", "no target, skip aim")
            }
        }
        InputInjector.reInjectTap(fireX, fireY)
    }

    private fun startScanLoop() {
        Logger.log("Service", "scan loop started")
        scope.launch {
            while (isActive) {
                val bmp = capture?.acquireLatestBitmap()
                if (bmp != null) {
                    frameCount++
                    val hit = detector?.detect(bmp)
                    bmp.recycle()
                    lastTarget = hit
                    if (hit != null) detectCount++
                    if (frameCount % 30 == 0) {
                        Logger.log("Service", "frames=$frameCount detects=$detectCount last=$lastTarget")
                    }
                    mainHandler.post { overlay?.updateDebugDot(hit) }
                }
                delay(FPS_DELAY)
            }
        }
    }

    override fun onDestroy() {
        Logger.log("Service", "onDestroy")
        job.cancel()
        mainHandler.post { overlay?.hide() }
        capture?.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "FFAimbot", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotif(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FFAimbot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotif(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(text))
    }
}
