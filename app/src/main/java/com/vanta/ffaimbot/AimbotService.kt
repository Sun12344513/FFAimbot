package com.vanta.ffaimbot

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AimbotService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE  = "result_code"
        const val EXTRA_DATA         = "data"
        private const val CHANNEL_ID = "ffaimbot"
        private const val NOTIF_ID   = 1
        private const val TAG        = "AimbotService"
        private const val FPS_DELAY  = 66L   // ~15 fps scan cadence

        private fun aimMs(): Long = 28L + (Math.random() * 16).toLong()
    }

    private var capture: ScreenCaptureManager? = null
    private var detector: EnemyDetector? = null
    private var overlay: OverlayManager? = null

    @Volatile private var enabled = false
    @Volatile private var lastTarget: PointF? = null

    private val scope       = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        InputInjector.init()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED

        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (code != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Bad projection data")
            stopSelf()
            return START_NOT_STICKY
        }

        val projMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection = projMgr.getMediaProjection(code, data) ?: run {
            Log.e(TAG, "getMediaProjection returned null")
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

    // Called when fire proxy overlay intercepts a touch in FF's fire zone.
    // Aim first, then re-inject the original tap so FF fires.
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

                // Jitter start ±20px to vary the injection signature
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
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        mainHandler.post { overlay?.hide() }
        capture?.stop()
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
