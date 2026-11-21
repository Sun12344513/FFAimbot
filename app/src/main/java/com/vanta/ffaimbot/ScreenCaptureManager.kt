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
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth  = bounds.width()
            screenHeight = bounds.height()
            screenDpi    = context.resources.displayMetrics.densityDpi
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenWidth  = dm.widthPixels
            screenHeight = dm.heightPixels
            screenDpi    = dm.densityDpi
        }

        Logger.log("Capture", "screen=${screenWidth}x${screenHeight} dpi=$screenDpi")

        handlerThread = HandlerThread("SCThread").also { it.start() }
        imageReader   = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        projection.createVirtualDisplay(
            "FFAimbot",
            screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            Handler(handlerThread.looper)
        )
        Logger.log("Capture", "VirtualDisplay created")
    }

    fun acquireLatestBitmap(): Bitmap? {
        val image = imageReader.acquireLatestImage()
        if (image == null) {
            return null   // normal — no new frame yet, don't spam log
        }
        return try {
            val plane       = image.planes[0]
            val buffer      = plane.buffer
            val rowStride   = plane.rowStride
            val pixelStride = plane.pixelStride

            val bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)

            if (rowStride == screenWidth * pixelStride) {
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                // Row padding — copy pixel-by-pixel per row stripping padding bytes
                val bpp       = pixelStride           // bytes per pixel (should be 4)
                val rowPixels = screenWidth * bpp
                val src       = ByteArray(rowStride)
                val dst       = ByteArray(screenWidth * screenHeight * 4)

                for (row in 0 until screenHeight) {
                    buffer.position(row * rowStride)
                    buffer.get(src, 0, rowStride)
                    System.arraycopy(src, 0, dst, row * rowPixels, rowPixels)
                }
                bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(dst))
            }
            bitmap
        } catch (e: Exception) {
            Logger.log("Capture", "acquireBitmap error: ${e.message}")
            null
        } finally {
            image.close()
        }
    }

    fun stop() {
        try { projection.stop()          } catch (e: Exception) { /* ignore */ }
        try { imageReader.close()        } catch (e: Exception) { /* ignore */ }
        try { handlerThread.quitSafely() } catch (e: Exception) { /* ignore */ }
        Logger.log("Capture", "stopped")
    }
}
