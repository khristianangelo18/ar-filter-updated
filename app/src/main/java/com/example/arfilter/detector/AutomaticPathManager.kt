// IMPROVED EnhancedPathManager.kt - More consistent path tracking

package com.example.arfilter.detector

import android.util.Log
import androidx.compose.ui.graphics.Color
import com.example.arfilter.utils.CsvReportManager
import com.example.arfilter.utils.SessionInfo
import kotlin.math.abs
import kotlin.math.sqrt

class EnhancedPathManager(
    private val maxActivePaths: Int = 1, // REDUCED to 1 for consistency
    private val pathTimeoutMs: Long = 8000L, // INCREASED timeout
    private val minPathPoints: Int = 10, // REDUCED for easier completion
    private val minRepDistance: Float = 0.06f, // REDUCED threshold
    private val maxJumpDistance: Float = 0.2f, // INCREASED to allow more movement
    private val trackingTolerance: Float = 0.15f // NEW: Tracking tolerance
) {
    private val activePaths = mutableListOf<BarPath>()
    private val completedReps = mutableListOf<BarPath>()
    private val repAnalyzer = SimpleRepAnalyzer()
    private var lastCleanupTime = 0L
    private var repCounter = 1
    private var sessionStartTime = 0L

    // IMPROVED: Better tracking state
    private var lastValidPoint: PathPoint? = null
    private var consecutiveMisses = 0
    private val maxConsecutiveMisses = 5 // Allow more misses before giving up

    companion object {
        private const val TAG = "EnhancedPathManager"
    }

    fun startSession() {
        sessionStartTime = System.currentTimeMillis()
        completedReps.clear()
        activePaths.clear()
        repCounter = 1
        lastValidPoint = null
        consecutiveMisses = 0
        Log.d(TAG, "New rep tracking session started")
    }

    fun addDetection(detection: Detection, currentTime: Long): List<BarPath> {
        val centerX = (detection.bbox.left + detection.bbox.right) / 2f
        val centerY = (detection.bbox.top + detection.bbox.bottom) / 2f
        val newPoint = PathPoint(centerX, centerY, currentTime)

        // IMPROVED: Better point validation
        if (!isValidNewPoint(newPoint)) {
            consecutiveMisses++
            Log.d(TAG, "Invalid point rejected, consecutive misses: $consecutiveMisses")
            return activePaths.toList()
        }

        consecutiveMisses = 0 // Reset on successful detection

        // Find closest active path or create new one
        val targetPath = findOrCreatePath(newPoint, currentTime)

        // Add point to path with validation
        if (targetPath != null) {
            targetPath.addPoint(newPoint)
            lastValidPoint = newPoint

            // Ensure path is in active list
            if (!activePaths.contains(targetPath)) {
                activePaths.add(targetPath)
            }

            Log.d(TAG, "Point added to path. Path now has ${targetPath.points.size} points")
        }

        // Check for completed reps more frequently
        checkForCompletedReps(currentTime)

        // Cleanup less frequently to maintain stability
        if (currentTime - lastCleanupTime > 3000L) {
            cleanupOldPaths(currentTime)
            lastCleanupTime = currentTime
        }

        return activePaths.toList()
    }

    // IMPROVED: Better point validation
    private fun isValidNewPoint(newPoint: PathPoint): Boolean {
        // Always accept the first point
        if (lastValidPoint == null) return true

        val lastPoint = lastValidPoint!!
        val distance = calculateDistance(newPoint, lastPoint)
        val timeDiff = newPoint.timestamp - lastPoint.timestamp

        // IMPROVED: More lenient distance checking
        if (distance > maxJumpDistance) {
            Log.d(TAG, "Point rejected: too far (distance: $distance)")
            return false
        }

        // IMPROVED: Time-based validation
        if (timeDiff > 0) {
            val speed = distance / (timeDiff / 1000f) // pixels per second
            if (speed > 2.0f) { // Allow higher speeds
                Log.d(TAG, "Point rejected: too fast (speed: $speed)")
                return false
            }
        }

        return true
    }

    // IMPROVED: Simplified path finding/creation
    private fun findOrCreatePath(newPoint: PathPoint, currentTime: Long): BarPath? {
        // Try to find existing path
        var bestPath: BarPath? = null
        var bestDistance = Float.MAX_VALUE

        for (path in activePaths) {
            if (path.points.isNotEmpty()) {
                val lastPoint = path.points.last()
                val timeDiff = newPoint.timestamp - lastPoint.timestamp

                // Only consider recent paths
                if (timeDiff < pathTimeoutMs) {
                    val distance = calculateDistance(newPoint, lastPoint)
                    if (distance < trackingTolerance && distance < bestDistance) {
                        bestDistance = distance
                        bestPath = path
                    }
                }
            }
        }

        // If found good path, use it
        if (bestPath != null) {
            return bestPath
        }

        // Create new path only if we don't have too many
        if (activePaths.size < maxActivePaths) {
            val newPath = BarPath(
                color = getColorForPathIndex(activePaths.size),
                startTime = currentTime
            )
            Log.d(TAG, "Created new path")
            return newPath
        }

        // Otherwise, use the most recent path
        return activePaths.maxByOrNull { path ->
            path.points.lastOrNull()?.timestamp ?: 0L
        }
    }

    // IMPROVED: More aggressive rep completion checking
    private fun checkForCompletedReps(currentTime: Long) {
        val pathsToCheck = activePaths.filter { path ->
            val timeSinceLastPoint = currentTime - (path.points.lastOrNull()?.timestamp ?: 0L)
            val hasEnoughPoints = path.points.size >= minPathPoints
            val hasBeenStable = timeSinceLastPoint > 2000L // 2 seconds of stability

            hasEnoughPoints && (hasBeenStable || path.points.size > 30) // Or if path is long enough
        }

        pathsToCheck.forEach { path ->
            if (isCompletedRep(path)) {
                val completedPath = path.copy()
                completedReps.add(completedPath)
                activePaths.remove(path)

                Log.d(TAG, "✅ COMPLETED REP #${repCounter} with ${path.points.size} points!")
                repCounter++
            }
        }
    }

    // IMPROVED: More lenient rep validation
    private fun isCompletedRep(path: BarPath): Boolean {
        val points = path.points
        if (points.size < minPathPoints) {
            Log.d(TAG, "Path too short: ${points.size} < $minPathPoints")
            return false
        }

        // Check for sufficient vertical movement
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val verticalRange = abs(maxY - minY)

        if (verticalRange < minRepDistance) {
            Log.d(TAG, "Insufficient vertical movement: $verticalRange < $minRepDistance")
            return false
        }

        // IMPROVED: Simpler pattern recognition
        val hasValidPattern = hasBasicUpDownPattern(points)
        if (!hasValidPattern) {
            Log.d(TAG, "No valid movement pattern detected")
            return false
        }

        // Duration validation (more lenient)
        val duration = (points.last().timestamp - points.first().timestamp) / 1000f
        if (duration < 0.5f || duration > 30.0f) {
            Log.d(TAG, "Invalid duration: $duration seconds")
            return false
        }

        Log.d(TAG, "✅ VALID REP: range=$verticalRange, duration=${duration}s, points=${points.size}")
        return true
    }

    // IMPROVED: Simplified pattern detection
    private fun hasBasicUpDownPattern(points: List<PathPoint>): Boolean {
        if (points.size < 6) return false

        val quarterSize = points.size / 4
        val firstQuarter = points.take(quarterSize)
        val lastQuarter = points.takeLast(quarterSize)
        val middle = points.drop(quarterSize).dropLast(quarterSize)

        if (firstQuarter.isEmpty() || lastQuarter.isEmpty() || middle.isEmpty()) return false

        val startY = firstQuarter.map { it.y }.average().toFloat()
        val endY = lastQuarter.map { it.y }.average().toFloat()
        val middleY = middle.map { it.y }.average().toFloat()

        // Look for either up-down or down-up pattern
        val isDownUp = middleY > startY && middleY > endY // Goes down then up
        val isUpDown = middleY < startY && middleY < endY // Goes up then down

        val minDifference = 0.03f // Minimum difference to consider valid
        val startToMiddle = abs(middleY - startY)
        val middleToEnd = abs(endY - middleY)

        val hasSignificantMovement = startToMiddle > minDifference || middleToEnd > minDifference

        Log.d(TAG, "Pattern check - Start: $startY, Middle: $middleY, End: $endY")
        Log.d(TAG, "IsDownUp: $isDownUp, IsUpDown: $isUpDown, HasMovement: $hasSignificantMovement")

        return (isDownUp || isUpDown) && hasSignificantMovement
    }

    // Rest of the functions remain the same...
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

    private fun calculateDistance(point1: PathPoint, point2: PathPoint): Float {
        val dx = point1.x - point2.x
        val dy = point1.y - point2.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun cleanupOldPaths(currentTime: Long) {
        val pathsToRemove = activePaths.filter { path ->
            val isOld = path.points.isEmpty() ||
                    currentTime - path.points.last().timestamp > pathTimeoutMs
            val isTooShortAndOld = path.points.size < (minPathPoints / 2) &&
                    currentTime - path.startTime > 5000L

            isOld || isTooShortAndOld
        }

        activePaths.removeAll(pathsToRemove)
        Log.d(TAG, "Cleaned up ${pathsToRemove.size} old paths")

        // Trim points from remaining paths
        activePaths.forEach { path ->
            if (path.points.size > 200) {
                val keepPoints = path.points.takeLast(150)
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
    fun getTotalPoints(): Int = activePaths.sumOf { it.points.size }

    fun clearAllPaths() {
        activePaths.clear()
        completedReps.clear()
        repCounter = 1
        lastValidPoint = null
        consecutiveMisses = 0
        Log.d(TAG, "All paths and completed reps cleared")
    }
}

data class SessionStats(
    val totalReps: Int,
    val averageQuality: Float,
    val sessionDuration: Float
 )