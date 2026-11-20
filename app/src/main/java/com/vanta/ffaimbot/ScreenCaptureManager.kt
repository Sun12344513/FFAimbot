// language: Kotlin, file: ScreenCaptureManager.kt
// Manages MediaProjection VirtualDisplay → ImageReader pipeline.
// Delivers RGBA bitmaps to the detector at ~15fps to stay under thermal budget.
package com.vanta.ffaimbot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
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
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDpi = 0

    fun start() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDpi = metrics.densityDpi

        handlerThread = HandlerThread("ScreenCapture").also { it.start() }

        // RGBA_8888 for direct bitmap conversion — no YUV decode step needed
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

    // Returns the most recent captured frame as a Bitmap, or null if none ready.
    // Caller is responsible for recycling.
    fun acquireLatestBitmap(): Bitmap? {
        val image = imageReader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            val bitmap = if (rowStride == screenWidth * pixelStride) {
                // Tight packing — direct copy
                Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888).also {
                    it.copyPixelsFromBuffer(buffer)
                }
            } else {
                // Row padding present — copy row by row
                val corrected = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
                val rowBytes = ByteArray(rowStride)
                val canvas = android.graphics.Canvas(corrected)
                val paint = android.graphics.Paint()
                for (row in 0 until screenHeight) {
                    buffer.position(row * rowStride)
                    buffer.get(rowBytes, 0, rowStride)
                    val rowBitmap = Bitmap.createBitmap(screenWidth, 1, Bitmap.Config.ARGB_8888)
                    rowBitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rowBytes))
                    canvas.drawBitmap(rowBitmap, 0f, row.toFloat(), paint)
                    rowBitmap.recycle()
                }
                corrected
            }
            bitmap
        } finally {
            image.close()
        }
    }

    fun stop() {
        projection.stop()
        imageReader.close()
        handlerThread.quitSafely()
    }

    fun getScreenSize(): Pair<Int, Int> = Pair(screenWidth, screenHeight)
}
