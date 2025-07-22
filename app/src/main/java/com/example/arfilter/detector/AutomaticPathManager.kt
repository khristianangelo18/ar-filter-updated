// ENHANCED EnhancedPathManager.kt - Better CSV Generation with Validation

package com.example.arfilter.detector

import android.util.Log
import androidx.compose.ui.graphics.Color
import com.example.arfilter.utils.CsvReportManager
import com.example.arfilter.utils.SessionInfo
import kotlin.math.abs
import kotlin.math.sqrt

class EnhancedPathManager(
    private val maxActivePaths: Int = 1,
    private val pathTimeoutMs: Long = 8000L,
    private val minPathPoints: Int = 10,
    private val minRepDistance: Float = 0.06f,
    private val maxJumpDistance: Float = 0.2f,
    private val trackingTolerance: Float = 0.15f
) {
    private val activePaths = mutableListOf<BarPath>()
    private val completedReps = mutableListOf<BarPath>()
    private val repAnalyzer = SimpleRepAnalyzer()
    private var lastCleanupTime = 0L
    private var repCounter = 1
    private var sessionStartTime = 0L

    private var lastValidPoint: PathPoint? = null
    private var consecutiveMisses = 0
    private val maxConsecutiveMisses = 5

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
        Log.d(TAG, "🟢 NEW SESSION STARTED - Rep tracking initialized")
    }

    fun addDetection(detection: Detection, currentTime: Long): List<BarPath> {
        val centerX = (detection.bbox.left + detection.bbox.right) / 2f
        val centerY = (detection.bbox.top + detection.bbox.bottom) / 2f
        val newPoint = PathPoint(centerX, centerY, currentTime)

        if (!isValidNewPoint(newPoint)) {
            consecutiveMisses++
            return activePaths.toList()
        }

        consecutiveMisses = 0
        val targetPath = findOrCreatePath(newPoint, currentTime)

        if (targetPath != null) {
            targetPath.addPoint(newPoint)
            lastValidPoint = newPoint

            if (!activePaths.contains(targetPath)) {
                activePaths.add(targetPath)
            }

            Log.v(TAG, "Point added: Path has ${targetPath.points.size} points")
        }

        checkForCompletedReps(currentTime)

        if (currentTime - lastCleanupTime > 3000L) {
            cleanupOldPaths(currentTime)
            lastCleanupTime = currentTime
        }

        return activePaths.toList()
    }

    private fun isValidNewPoint(newPoint: PathPoint): Boolean {
        if (lastValidPoint == null) return true

        val lastPoint = lastValidPoint!!
        val distance = calculateDistance(newPoint, lastPoint)
        val timeDiff = newPoint.timestamp - lastPoint.timestamp

        if (distance > maxJumpDistance) {
            return false
        }

        if (timeDiff > 0) {
            val speed = distance / (timeDiff / 1000f)
            if (speed > 2.0f) {
                return false
            }
        }

        return true
    }

    private fun findOrCreatePath(newPoint: PathPoint, currentTime: Long): BarPath? {
        var bestPath: BarPath? = null
        var bestDistance = Float.MAX_VALUE

        for (path in activePaths) {
            if (path.points.isNotEmpty()) {
                val lastPoint = path.points.last()
                val timeDiff = newPoint.timestamp - lastPoint.timestamp

                if (timeDiff < pathTimeoutMs) {
                    val distance = calculateDistance(newPoint, lastPoint)
                    if (distance < trackingTolerance && distance < bestDistance) {
                        bestDistance = distance
                        bestPath = path
                    }
                }
            }
        }

        if (bestPath != null) {
            return bestPath
        }

        if (activePaths.size < maxActivePaths) {
            val newPath = BarPath(
                color = getColorForPathIndex(activePaths.size),
                startTime = currentTime
            )
            Log.d(TAG, "Created new path")
            return newPath
        }

        return activePaths.maxByOrNull { path ->
            path.points.lastOrNull()?.timestamp ?: 0L
        }
    }

    private fun checkForCompletedReps(currentTime: Long) {
        val pathsToCheck = activePaths.filter { path ->
            val timeSinceLastPoint = currentTime - (path.points.lastOrNull()?.timestamp ?: 0L)
            val hasEnoughPoints = path.points.size >= minPathPoints
            val hasBeenStable = timeSinceLastPoint > 2000L

            hasEnoughPoints && (hasBeenStable || path.points.size > 30)
        }

        pathsToCheck.forEach { path ->
            if (isCompletedRep(path)) {
                val completedPath = path.copy()
                completedReps.add(completedPath)
                activePaths.remove(path)

                Log.d(TAG, "✅ COMPLETED REP #${repCounter} with ${path.points.size} points!")
                Log.d(TAG, "📊 Total completed reps: ${completedReps.size}")
                repCounter++
            }
        }
    }

    private fun isCompletedRep(path: BarPath): Boolean {
        val points = path.points
        if (points.size < minPathPoints) {
            return false
        }

        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val verticalRange = abs(maxY - minY)

        if (verticalRange < minRepDistance) {
            return false
        }

        val hasValidPattern = hasBasicUpDownPattern(points)
        if (!hasValidPattern) {
            return false
        }

        val duration = (points.last().timestamp - points.first().timestamp) / 1000f
        if (duration < 0.5f || duration > 30.0f) {
            return false
        }

        return true
    }

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

        val isDownUp = middleY > startY && middleY > endY
        val isUpDown = middleY < startY && middleY < endY

        val minDifference = 0.03f
        val startToMiddle = abs(middleY - startY)
        val middleToEnd = abs(endY - middleY)

        val hasSignificantMovement = startToMiddle > minDifference || middleToEnd > minDifference

        return (isDownUp || isUpDown) && hasSignificantMovement
    }

    // ENHANCED: Better CSV generation with validation and error handling
    suspend fun generateReport(
        csvManager: CsvReportManager,
        exercise: String,
        tempo: String
    ): String? {
        Log.d(TAG, "🔄 STARTING CSV GENERATION")
        Log.d(TAG, "📊 Completed reps count: ${completedReps.size}")

        // ENHANCED: Better validation
        if (completedReps.isEmpty()) {
            Log.w(TAG, "⚠️ No completed reps to generate report")
            return null
        }

        // ENHANCED: Validate that we have actual path data
        val validReps = completedReps.filter { it.points.isNotEmpty() }
        if (validReps.isEmpty()) {
            Log.w(TAG, "⚠️ No valid rep data (all reps have empty points)")
            return null
        }

        try {
            // Convert completed paths to RepData with detailed logging
            val repDataList = mutableListOf<RepData>()

            validReps.forEachIndexed { index, barPath ->
                Log.d(TAG, "🔍 Analyzing rep ${index + 1}: ${barPath.points.size} points, duration: ${barPath.getDuration()}ms")

                val repData = repAnalyzer.analyzeRep(
                    barPath = barPath,
                    exercise = exercise,
                    tempo = tempo,
                    repNumber = index + 1
                )

                if (repData != null) {
                    repDataList.add(repData)
                    Log.d(TAG, "✅ Rep ${index + 1} analyzed: Quality=${repData.qualityScore}, Distance=${repData.totalDistance}cm")
                } else {
                    Log.w(TAG, "❌ Failed to analyze rep ${index + 1}")
                }
            }

            if (repDataList.isEmpty()) {
                Log.e(TAG, "💥 No valid rep data generated from ${validReps.size} completed reps")
                return null
            }

            Log.d(TAG, "📈 Generated ${repDataList.size} valid rep data entries")

            // Create session info
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

            // Generate the CSV report
            Log.d(TAG, "📄 Generating CSV file...")
            val reportPath = csvManager.generateReport(repDataList, sessionInfo)

            if (reportPath != null) {
                Log.d(TAG, "✅ CSV report generated successfully: $reportPath")
                Log.d(TAG, "📋 Report contains ${repDataList.size} reps with session duration: $sessionDuration")
                return reportPath
            } else {
                Log.e(TAG, "❌ CSV generation failed - csvManager returned null")
                return null
            }

        } catch (e: Exception) {
            Log.e(TAG, "💥 Exception during CSV generation: ${e.message}", e)
            return null
        }
    }

    fun getSessionStats(): SessionStats {
        val validReps = completedReps.size
        val averageQuality = if (validReps > 0) {
            completedReps.mapNotNull { path ->
                try {
                    repAnalyzer.analyzeRep(path, "", "", 0)?.qualityScore
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to analyze rep for stats: ${e.message}")
                    null
                }
            }.average().toFloat()
        } else 0f

        val sessionDuration = if (sessionStartTime > 0) {
            (System.currentTimeMillis() - sessionStartTime) / 1000f
        } else 0f

        return SessionStats(
            totalReps = validReps,
            averageQuality = averageQuality,
            sessionDuration = sessionDuration
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
        Log.d(TAG, "🧹 All paths and completed reps cleared")
    }
}

data class SessionStats(
    val totalReps: Int,
    val averageQuality: Float,
    val sessionDuration: Float
)