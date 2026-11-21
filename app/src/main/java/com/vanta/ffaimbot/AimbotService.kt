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
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AimbotService : Service() {

    companion object {
        private const val CHANNEL_ID = "ffaimbot"
        private const val NOTIF_ID   = 1
        private const val TAG        = "AimbotService"
        private const val FPS_DELAY  = 66L

        private fun aimMs(): Long = 28L + (Math.random() * 16).toLong()
    }

    private var capture: ScreenCaptureManager? = null
    private var detector: EnemyDetector? = null
    private var overlay: OverlayManager? = null

    @Volatile private var enabled = false
    @Volatile private var lastTarget: PointF? = null

    private val job         = SupervisorJob()
    private val scope       = CoroutineScope(Dispatchers.Default + job)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        InputInjector.init()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Read projection from singleton — already created in MainActivity.onActivityResult.
        // Never call getMediaProjection() here: on MIUI the token is consumed on first call.
        val projection: MediaProjection = ProjectionHolder.projection ?: run {
            Log.e(TAG, "ProjectionHolder is null — was MainActivity flow skipped?")
            stopSelf()
            return START_NOT_STICKY
        }

        capture  = ScreenCaptureManager(this, projection).also { it.start() }
        detector = EnemyDetector(capture!!.screenWidth, capture!!.screenHeight)
        overlay  = OverlayManager(
            context           = this,
            onFireIntercepted = ::onFire,
            onAimbotToggled   = { on -> enabled = on }
        )

        mainHandler.post { overlay?.show() }
        startScanLoop()
        return START_STICKY
    }

    private fun onFire(fireX: Float, fireY: Float) {
        if (enabled) {
            val target = lastTarget
            if (target != null) {
                val w = capture?.screenWidth  ?: return
                val h = capture?.screenHeight ?: return
                val cx = w / 2f
                val cy = h / 2f

                val dx = (target.x - cx).coerceIn(-400f, 400f)
                val dy = (target.y - cy).coerceIn(-400f, 400f)

                val jx = (Math.random() * 40 - 20).toFloat()
                val jy = (Math.random() * 40 - 20).toFloat()
                val from = PointF(cx + jx, cy + jy)
                val to   = PointF(from.x + dx * 0.6f, from.y + dy * 0.6f)

                InputInjector.swipeAim(from, to, aimMs())
            }
        }
        InputInjector.reInjectTap(fireX, fireY)
    }

    private fun startScanLoop() {
        scope.launch {
            while (isActive) {
                val bmp = capture?.acquireLatestBitmap()
                if (bmp != null) {
                    val hit = detector?.detect(bmp)
                    lastTarget = hit
                    bmp.recycle()
                    mainHandler.post { overlay?.updateDebugDot(hit) }
                }
                delay(FPS_DELAY)
            }
        }
    }

    override fun onDestroy() {
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
