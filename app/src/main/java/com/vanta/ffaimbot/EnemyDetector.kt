package com.vanta.ffaimbot

import android.graphics.Bitmap
import android.graphics.PointF

class EnemyDetector(
    private val screenWidth: Int,
    private val screenHeight: Int
) {

    // Scan the upper 65% of screen — enemy markers appear here when aiming normally
    private val scanTop    = (screenHeight * 0.05f).toInt()
    private val scanBottom = (screenHeight * 0.65f).toInt()
    private val scanLeft   = (screenWidth  * 0.05f).toInt()
    private val scanRight  = (screenWidth  * 0.95f).toInt()

    private val minCluster  = 20   // minimum pixels to count as a valid marker
    private val mergeRadius = 60   // pixels — nearby hits merge into one cluster

    private data class Cluster(
        var sumX: Long,
        var sumY: Long,
        var count: Int
    )

    fun detect(bitmap: Bitmap): PointF? {
        val clusters = mutableListOf<Cluster>()

        // Step-3 pixel scan: ~9x faster than full scan, adequate at 15 fps
        var x = scanLeft
        while (x < scanRight) {
            var y = scanTop
            while (y < scanBottom) {
                if (isEnemyColor(bitmap.getPixel(x, y))) {
                    val existing = clusters.firstOrNull { c ->
                        val cx = c.sumX / c.count
                        val cy = c.sumY / c.count
                        Math.abs(cx - x) < mergeRadius && Math.abs(cy - y) < mergeRadius
                    }
                    if (existing != null) {
                        existing.sumX += x
                        existing.sumY += y
                        existing.count++
                    } else {
                        clusters.add(Cluster(x.toLong(), y.toLong(), 1))
                    }
                }
                y += 3
            }
            x += 3
        }

        val best = clusters.filter { it.count >= minCluster }
            .maxByOrNull { it.count } ?: return null

        return PointF(
            (best.sumX / best.count).toFloat(),
            (best.sumY / best.count).toFloat()
        )
    }

    // FF OB55 enemy color signatures:
    //   Red   — health bar:      R>200, G<50,  B<50
    //   Orange — enemy outline:  R>200, G 80-140, B<60
    // Re-tune ranges if a patch changes the enemy shader colors
    private fun isEnemyColor(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8)  and 0xFF
        val b =  pixel         and 0xFF
        val redBar     = r > 200 && g < 50  && b < 50
        val orangeMark = r > 200 && g in 80..140 && b < 60
        return redBar || orangeMark
    }
}
