// language: Kotlin, file: AimbotService.kt
// Foreground service — owns the screen capture loop, enemy detection, and input injection.
// Lifecycle: started from MainActivity after all permissions confirmed.
package com.vanta.ffaimbot

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class AimbotService : Service() {

    companion object {
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_PROJECTION_RESULT = "projection_result"
        private const val CHANNEL_ID = "aimbot_channel"
        private const val NOTIF_ID = 1
        private const val TAG = "AimbotService"

        // Detection + injection cadence — 15fps is enough for frame scan
        private const val FRAME_INTERVAL_MS = 66L

        // Swipe duration for aim snap — randomized ±8ms per shot to vary timing signature
        private fun aimDurationMs() = 28L + (Math.random() * 16).toLong()
    }

    private var captureManager: ScreenCaptureManager? = null
    private var enemyDetector: EnemyDetector? = null
    private var overlayManager: OverlayManager? = null
    private var aimbotEnabled = false
    private var lastDetected: PointF? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        InputInjector.init()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Missing MediaProjection data — stopping")
            stopSelf(); return START_NOT_STICKY
        }

        val projMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projMgr.getMediaProjection(resultCode, data)

        captureManager = ScreenCaptureManager(this, projection).also { it.start() }
        val (sw, sh) = captureManager!!.getScreenSize()
        enemyDetector = EnemyDetector(sw, sh)

        overlayManager = OverlayManager(
            context = this,
            onFireIntercepted = { x, y -> handleFireProxy(x, y) },
            onAimbotToggled = { enabled -> aimbotEnabled = enabled }
        )
        mainHandler.post { overlayManager?.show() }

        startDetectionLoop()
        return START_STICKY
    }

    // Called by OverlayManager when the fire button region is touched.
    // Aim → re-inject fire tap. Runs on whatever thread the touch arrived on.
    private fun handleFireProxy(fireX: Float, fireY: Float) {
        if (aimbotEnabled) {
            val target = lastDetected
            if (target != null) {
                val (sw, sh) = captureManager?.getScreenSize() ?: Pair(0, 0)
                // Crosshair sits at screen center — aim swipe from center toward target
                val centerX = sw / 2f
                val centerY = sh / 2f

                // Clamp delta — large swipes cause camera overcorrection
                val dx = (target.x - centerX).coerceIn(-400f, 400f)
                val dy = (target.y - centerY).coerceIn(-400f, 400f)

                // Swipe starts from the right half of screen (look area in FF)
                // Offset start point randomly ±20px to avoid a fixed-origin detection signature
                val jitterX = (Math.random() * 40 - 20).toFloat()
                val jitterY = (Math.random() * 40 - 20).toFloat()
                val swipeStart = PointF(centerX + jitterX, centerY + jitterY)
                val swipeEnd = PointF(swipeStart.x + dx * 0.6f, swipeStart.y + dy * 0.6f)
                // 0.6 multiplier — partial correction feels more natural, avoids overshooting

                InputInjector.swipeAim(swipeStart, swipeEnd, aimDurationMs())
            }
        }
        // Always re-inject the fire tap so FF actually fires
        InputInjector.reInjectTap(fireX, fireY)
    }

    // Coroutine loop — capture frame, detect, cache result, update debug dot
    private fun startDetectionLoop() {
        serviceScope.launch {
            while (isActive) {
                val bitmap = captureManager?.acquireLatestBitmap()
                if (bitmap != null) {
                    val detected = enemyDetector?.detect(bitmap)
                    lastDetected = detected
                    bitmap.recycle()
                    mainHandler.post { overlayManager?.updateDebugDot(detected) }
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mainHandler.post { overlayManager?.hide() }
        captureManager?.stop()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Aimbot", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FFAimbot Active")
            .setContentText("Overlay running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
