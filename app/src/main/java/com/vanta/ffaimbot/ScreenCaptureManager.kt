package com.vanta.ffaimbot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager

class ScreenCaptureManager(
    private val context: Context,
    private val projection: MediaProjection
) {

    private lateinit var imageReader: ImageReader
    private lateinit var handlerThread: HandlerThread

    var screenWidth = 0
        private set
    var screenHeight = 0
        private set
    private var screenDpi = 0

    fun start() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDpi = context.resources.displayMetrics.densityDpi
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
            screenDpi = dm.densityDpi
        }

        handlerThread = HandlerThread("SCThread").also { it.start() }
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        projection.createVirtualDisplay(
            "FFAimbot",
            screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            Handler(handlerThread.looper)
        )
    }

    // Returns the latest captured frame as a Bitmap. Caller must call recycle() after use.
    fun acquireLatestBitmap(): Bitmap? {
        val image = imageReader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)

            if (rowStride == screenWidth * pixelStride) {
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                // Row padding present — strip padding per row
                val rowBytes = ByteArray(rowStride)
                val stripped = ByteArray(screenWidth * screenHeight * 4)
                for (row in 0 until screenHeight) {
                    buffer.position(row * rowStride)
                    buffer.get(rowBytes)
                    System.arraycopy(rowBytes, 0, stripped, row * screenWidth * 4, screenWidth * 4)
                }
                bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(stripped))
            }
            bitmap
        } finally {
            image.close()
        }
    }

    fun stop() {
        try { projection.stop() } catch (e: Exception) { /* ignore */ }
        try { imageReader.close() } catch (e: Exception) { /* ignore */ }
        try { handlerThread.quitSafely() } catch (e: Exception) { /* ignore */ }
    }
}
