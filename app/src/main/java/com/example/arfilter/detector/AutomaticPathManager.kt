package com.example.arfilter.detector

import android.util.Log
import androidx.compose.ui.graphics.Color
import com.example.arfilter.utils.CsvReportManager
import com.example.arfilter.utils.SessionInfo

/**
 * Enhanced path manager that tracks completed reps for CSV reporting
 * Extends the original AutomaticPathManager with rep completion detection
 */
class EnhancedPathManager(
    private val maxActivePaths: Int = 2,
    private val pathTimeoutMs: Long = 4000L,
    private val minPathPoints: Int = 15,
    private val minRepDistance: Float = 0.08f // Minimum vertical movement for a valid rep
) {
    private val activePaths = mutableListOf<BarPath>()
    private val completedReps = mutableListOf<BarPath>()
    private val repAnalyzer = SimpleRepAnalyzer()
    private var lastCleanupTime = 0L
    private var repCounter = 1
    private var sessionStartTime = 0L

    companion object {
        private const val TAG = "EnhancedPathManager"
    }

    /**
     * Start a new session for rep tracking
     */
    fun startSession() {
        sessionStartTime = System.currentTimeMillis()
        completedReps.clear()
        activePaths.clear()
        repCounter = 1
        Log.d(TAG, "New rep tracking session started")
    }

    /**
     * Add detection and check for completed reps
     */
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

        // Check for completed reps
        checkForCompletedReps(currentTime)

        // Cleanup periodically
        if (currentTime - lastCleanupTime > 2000L) {
            cleanupOldPaths(currentTime)
            lastCleanupTime = currentTime
        }

        return activePaths.toList()
    }

    /**
     * Check if any paths represent completed reps
     */
    private fun checkForCompletedReps(currentTime: Long) {
        val pathsToCheck = activePaths.filter { path ->
            val timeSinceLastPoint = currentTime - (path.points.lastOrNull()?.timestamp ?: 0L)
            val hasEnoughPoints = path.points.size >= minPathPoints
            val hasBeenStable = timeSinceLastPoint > 1500L // 1.5 seconds of stability

            hasEnoughPoints && hasBeenStable
        }

        pathsToCheck.forEach { path ->
            if (isCompletedRep(path)) {
                // Mark as completed rep
                val completedPath = path.copy().apply {
                    // Mark with unique ID and rep number
                    val pathWithRepNumber = this
                }

                completedReps.add(completedPath)
                activePaths.remove(path)

                Log.d(TAG, "Completed rep detected! Rep #${repCounter}")
                repCounter++
            }
        }
    }

    /**
     * Check if a path represents a valid completed rep
     */
    private fun isCompletedRep(path: BarPath): Boolean {
        val points = path.points
        if (points.size < minPathPoints) return false

        // Check for sufficient vertical movement
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val verticalRange = kotlin.math.abs(maxY - minY)

        if (verticalRange < minRepDistance) {
            Log.d(TAG, "Path rejected: insufficient vertical movement ($verticalRange < $minRepDistance)")
            return false
        }

        // Check for complete movement pattern (down and up)
        val firstHalf = points.take(points.size / 2)
        val secondHalf = points.drop(points.size / 2)

        val firstHalfTrend = (firstHalf.last().y - firstHalf.first().y)
        val secondHalfTrend = (secondHalf.last().y - secondHalf.first().y)

        // Look for down then up pattern OR up then down pattern
        val hasValidPattern = (firstHalfTrend > 0 && secondHalfTrend < 0) ||
                (firstHalfTrend < 0 && secondHalfTrend > 0)

        if (!hasValidPattern) {
            Log.d(TAG, "Path rejected: no valid movement pattern")
            return false
        }

        // Check duration (reasonable rep time)
        val duration = (points.last().timestamp - points.first().timestamp) / 1000f
        if (duration < 1.0f || duration > 15.0f) {
            Log.d(TAG, "Path rejected: invalid duration ($duration seconds)")
            return false
        }

        Log.d(TAG, "Valid rep detected: range=$verticalRange, duration=$duration, points=${points.size}")
        return true
    }

    /**
     * Generate CSV report for completed reps
     */
    suspend fun generateReport(
        csvManager: CsvReportManager,
        exercise: String,
        tempo: String
    ): String? {
        if (completedReps.isEmpty()) {
            Log.d(TAG, "No completed reps to report")
            return null
        }

        val repDataList = completedReps.mapIndexedNotNull { index, barPath ->
            repAnalyzer.analyzeRep(
                barPath = barPath,
                exercise = exercise,
                tempo = tempo,
                repNumber = index + 1
            )
        }

        if (repDataList.isEmpty()) {
            Log.d(TAG, "No valid rep data to report")
            return null
        }

        val sessionDuration = if (sessionStartTime > 0) {
            val durationMs = System.currentTimeMillis() - sessionStartTime
            val minutes = durationMs / (1000 * 60)
            val seconds = (durationMs % (1000 * 60)) / 1000
            "${minutes}m ${seconds}s"
        } else "Unknown"

        val sessionInfo = SessionInfo(
            exercise = exercise,
            tempo = tempo,
            duration = sessionDuration
        )

        return csvManager.generateReport(repDataList, sessionInfo)
    }

    /**
     * Get statistics for current session
     */
    fun getSessionStats(): SessionStats {
        return SessionStats(
            totalReps = completedReps.size,
            averageQuality = if (completedReps.isNotEmpty()) {
                completedReps.mapNotNull { path ->
                    repAnalyzer.analyzeRep(path, "", "", 0)?.qualityScore
                }.average().toFloat()
            } else 0f,
            sessionDuration = if (sessionStartTime > 0) {
                (System.currentTimeMillis() - sessionStartTime) / 1000f
            } else 0f
        )
    }

    // Original AutomaticPathManager methods (unchanged)
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
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun createNewPath(currentTime: Long): BarPath {
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
    fun getCompletedReps(): List<BarPath> = completedReps.toList()
    fun getActivePathCount(): Int = activePaths.size
    fun getTotalPoints(): Int = activePaths.map { it.points.size }.sum()

    fun clearAllPaths() {
        activePaths.clear()
        completedReps.clear()
        repCounter = 1
        Log.d(TAG, "All paths and completed reps cleared")
    }
}

/**
 * Session statistics for display
 */
data class SessionStats(
    val totalReps: Int,
    val averageQuality: Float,
    val sessionDuration: Float
)