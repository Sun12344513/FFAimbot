package com.vanta.ffaimbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PointF
import android.media.projection.MediaProjection
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
        private const val CHANNEL_ID = "ffaimbot"
        private const val NOTIF_ID   = 1
        private const val FPS_DELAY  = 66L

        private fun aimMs(): Long = 28L + (Math.random() * 16).toLong()
    }

    private var capture: ScreenCaptureManager? = null
    private var detector: EnemyDetector? = null
    private var overlay: OverlayManager? = null

    @Volatile private var enabled    = false
    @Volatile private var lastTarget: PointF? = null
    private var frameCount  = 0
    private var detectCount = 0

    private val job         = SupervisorJob()
    private val scope       = CoroutineScope(Dispatchers.Default + job)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        Logger.log("Service", "onCreate")

        val injOk = InputInjector.init()
        Logger.log("Service", "InputInjector.init = $injOk")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.log("Service", "onStartCommand")

        val projection: MediaProjection = ProjectionHolder.projection ?: run {
            Logger.log("Service", "FAIL: ProjectionHolder.projection is null")
            stopSelf()
            return START_NOT_STICKY
        }

        Logger.log("Service", "starting ScreenCaptureManager")
        capture  = ScreenCaptureManager(this, projection).also { it.start() }
        Logger.log("Service", "screen: ${capture!!.screenWidth}x${capture!!.screenHeight}")

        detector = EnemyDetector(capture!!.screenWidth, capture!!.screenHeight)
        overlay  = OverlayManager(
            context           = this,
            onFireIntercepted = ::onFire,
            onAimbotToggled   = { on ->
                enabled = on
                Logger.log("Service", "aimbot enabled=$on")
            }
        )

        mainHandler.post { overlay?.show() }
        startScanLoop()
        return START_STICKY
    }

    private fun onFire(fireX: Float, fireY: Float) {
        Logger.log("Service", "onFire fireX=$fireX fireY=$fireY enabled=$enabled target=$lastTarget")
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

                Logger.log("Service", "swipeAim from=(${from.x},${from.y}) to=(${to.x},${to.y})")
                InputInjector.swipeAim(from, to, aimMs())
            } else {
                Logger.log("Service", "no target detected, skipping aim")
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
                    if (hit != null) {
                        detectCount++
                        lastTarget = hit
                    } else {
                        lastTarget = null
                    }
                    // Log stats every 30 frames (~2s)
                    if (frameCount % 30 == 0) {
                        Logger.log("Service", "frames=$frameCount detections=$detectCount lastTarget=$lastTarget")
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
        ProjectionHolder.projection = null
        super.onDestroy()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Aimbot", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotif(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FFAimbot")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
