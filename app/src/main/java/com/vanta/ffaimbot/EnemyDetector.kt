// language: Kotlin, file: EnemyDetector.kt
// Scans bitmap frames for FF enemy markers — health bars (red) and enemy outline (orange-red).
// Returns centroid of the largest qualifying cluster, or null if no enemy detected.
// Scan every 3rd pixel — ~9x speedup vs full scan, adequate for 15fps cadence.
package com.vanta.ffaimbot

import android.graphics.Bitmap
import android.graphics.PointF

class EnemyDetector(private val screenWidth: Int, private val screenHeight: Int) {

    // Detection zone: exclude bottom 35% (UI) and extreme edges
    // Enemies appear in the upper ~65% of screen when aiming normally
    private val scanTop = (screenHeight * 0.05f).toInt()
    private val scanBottom = (screenHeight * 0.65f).toInt()
    private val scanLeft = (screenWidth * 0.05f).toInt()
    private val scanRight = (screenWidth * 0.95f).toInt()

    // Minimum cluster pixel count to register as a valid enemy marker
    // Too low = false positives on muzzle flash/blood. 20px cluster ~ 4x4 health bar segment.
    private val minClusterSize = 20

    data class Cluster(var sumX: Long, var sumY: Long, var count: Int) {
        fun centroid() = PointF(sumX.toFloat() / count, sumY.toFloat() / count)
    }

    // Returns aim point in screen coordinates, or null
    fun detect(bitmap: Bitmap): PointF? {
        val clusters = mutableListOf<Cluster>()

        // Step-3 pixel scan over detection zone
        var x = scanLeft
        while (x < scanRight) {
            var y = scanTop
            while (y < scanBottom) {
                val px = bitmap.getPixel(x, y)
                if (isEnemyColor(px)) {
                    // Merge into existing nearby cluster or create new
                    val merged = clusters.firstOrNull { c ->
                        val cx = c.sumX / c.count
                        val cy = c.sumY / c.count
                        Math.abs(cx - x) < 60 && Math.abs(cy - y) < 60
                    }
                    if (merged != null) {
                        merged.sumX += x; merged.sumY += y; merged.count++
                    } else {
                        clusters.add(Cluster(x.toLong(), y.toLong(), 1))
                    }
                }
                y += 3
            }
            x += 3
        }

        // Largest cluster above minimum size
        val best = clusters.filter { it.count >= minClusterSize }
            .maxByOrNull { it.count } ?: return null

        return best.centroid()
    }

    // FF OB55 enemy markers:
    //   Health bar: pure red — R>200, G<50, B<50
    //   Enemy outline/triangle: orange-red — R>200, G>80, G<140, B<60
    // Alpha channel ignored (always 0xFF from ARGB_8888 buffer)
    private fun isEnemyColor(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val isRedHealthBar = r > 200 && g < 50 && b < 50
        val isOrangeMarker = r > 200 && g in 80..140 && b < 60
        return isRedHealthBar || isOrangeMarker
    }
}
