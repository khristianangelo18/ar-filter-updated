package com.example.arfilter.detector

import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlin.math.sqrt

/**
 * Simplified automatic path manager for ARFilter background detection
 * Manages bar path tracking without interfering with main AR functionality
 */
class AutomaticPathManager(
    private val maxActivePaths: Int = 2,
    private val pathTimeoutMs: Long = 4000L,
    private val minPathPoints: Int = 8
) {
    private val activePaths = mutableListOf<BarPath>()
    private var lastCleanupTime = 0L

    companion object {
        private const val TAG = "AutoPathManager"
    }

    fun addDetection(detection: Detection, currentTime: Long): List<BarPath> {
        val centerX = (detection.bbox.left + detection.bbox.right) / 2f
        val centerY = (detection.bbox.top + detection.bbox.bottom) / 2f
        val newPoint = PathPoint(centerX, centerY, currentTime)

        // Find closest active path or create new one
        val targetPath = findClosestPath(newPoint) ?: createNewPath(currentTime)

        // Add point to path
        targetPath.addPoint(newPoint)

        // Ensure path is in active list
        if (!activePaths.contains(targetPath)) {
            activePaths.add(targetPath)
        }

        // Cleanup old paths periodically
        if (currentTime - lastCleanupTime > 2000L) {
            cleanupOldPaths(currentTime)
            lastCleanupTime = currentTime
        }

        return activePaths.toList()
    }

    private fun findClosestPath(newPoint: PathPoint): BarPath? {
        if (activePaths.isEmpty()) return null

        val recentPaths = activePaths.filter { path ->
            path.points.isNotEmpty() &&
                    newPoint.timestamp - path.points.last().timestamp < 2000L
        }

        return recentPaths.minByOrNull { path ->
            if (path.points.isNotEmpty()) {
                calculateDistance(newPoint, path.points.last())
            } else Float.MAX_VALUE
        }?.takeIf { path ->
            if (path.points.isNotEmpty()) {
                calculateDistance(newPoint, path.points.last()) < 0.1f
            } else false
        }
    }

    private fun calculateDistance(point1: PathPoint, point2: PathPoint): Float {
        val dx = point1.x - point2.x
        val dy = point1.y - point2.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun createNewPath(currentTime: Long): BarPath {
        // Limit number of active paths
        if (activePaths.size >= maxActivePaths) {
            val oldestPath = activePaths.minByOrNull { path ->
                if (path.points.isNotEmpty()) path.points.last().timestamp else 0L
            }
            oldestPath?.let { activePaths.remove(it) }
        }

        return BarPath(
            color = getColorForPathIndex(activePaths.size),
            startTime = currentTime
        )
    }

    private fun cleanupOldPaths(currentTime: Long) {
        activePaths.removeAll { path ->
            val isOld = path.points.isEmpty() ||
                    currentTime - path.points.last().timestamp > pathTimeoutMs
            val isTooShort = path.points.size < minPathPoints &&
                    currentTime - path.startTime > 3000L

            isOld || isTooShort
        }

        // Trim points from remaining paths
        activePaths.forEach { path ->
            if (path.points.size > 300) {
                val keepPoints = path.points.takeLast(200)
                path.points.clear()
                path.points.addAll(keepPoints)
            }
        }
    }

    private fun getColorForPathIndex(index: Int): Color {
        val colors = listOf(Color.Cyan, Color.Yellow, Color.Green, Color.Magenta)
        return colors[index % colors.size]
    }

    fun getCurrentPaths(): List<BarPath> = activePaths.toList()

    fun clearAllPaths() {
        activePaths.clear()
        Log.d(TAG, "All paths cleared")
    }

    fun getActivePathCount(): Int = activePaths.size

    fun getTotalPoints(): Int = activePaths.map { it.points.size }.sum()
}