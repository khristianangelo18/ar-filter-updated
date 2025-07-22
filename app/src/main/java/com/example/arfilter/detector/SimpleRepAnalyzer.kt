package com.example.arfilter.detector

import android.util.Log
import kotlin.math.*

/**
 * SIMPLIFIED SimpleRepAnalyzer - Focused on essential metrics only
 */
class SimpleRepAnalyzer {

    companion object {
        private const val TAG = "SimpleRepAnalyzer"
    }

    /**
     * SIMPLIFIED: Analyze a completed rep with only essential metrics
     */
    fun analyzeRep(
        barPath: BarPath,
        exercise: String,
        tempo: String,
        repNumber: Int
    ): RepData? {

        val points = barPath.points

        Log.d(TAG, "🔍 Analyzing rep $repNumber: ${points.size} points")

        if (points.size < 5) {
            Log.w(TAG, "❌ Rep $repNumber rejected: insufficient points (${points.size} < 5)")
            return null
        }

        try {
            // Convert normalized coordinates to approximate centimeters
            // Assume average screen height represents about 60cm of movement
            val pixelToCm = 60f

            // ESSENTIAL Movement Metrics (SIMPLIFIED)
            val totalDistance = calculateTotalDistance(points) * pixelToCm
            val verticalRange = calculateVerticalRange(points) * pixelToCm

            // Validate basic metrics
            if (totalDistance < 1f || verticalRange < 0.5f) {
                Log.w(TAG, "❌ Rep $repNumber rejected: invalid metrics (distance=$totalDistance, range=$verticalRange)")
                return null
            }

            // SIMPLIFIED Quality Score (based on movement consistency and completeness)
            val qualityScore = calculateSimplifiedQualityScore(points, totalDistance, verticalRange)

            val repData = RepData(
                repNumber = repNumber,
                exercise = exercise,
                tempo = tempo,
                totalDistance = totalDistance,
                verticalRange = verticalRange,
                qualityScore = qualityScore
            )

            Log.d(TAG, "✅ Rep $repNumber analyzed successfully:")
            Log.d(TAG, "   - Distance: ${String.format("%.1f", totalDistance)}cm")
            Log.d(TAG, "   - Range: ${String.format("%.1f", verticalRange)}cm")
            Log.d(TAG, "   - Quality: ${String.format("%.0f", qualityScore)}%")

            return repData

        } catch (e: Exception) {
            Log.e(TAG, "💥 Error analyzing rep $repNumber: ${e.message}", e)
            return null
        }
    }

    private fun calculateTotalDistance(points: List<PathPoint>): Float {
        if (points.size < 2) return 0f
        return points.zipWithNext { a, b -> a.distanceTo(b) }.sum()
    }

    private fun calculateVerticalRange(points: List<PathPoint>): Float {
        if (points.isEmpty()) return 0f
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        return abs(maxY - minY)
    }

    /**
     * SIMPLIFIED quality score calculation
     * Based on:
     * 1. Movement completeness (vertical range)
     * 2. Path efficiency (total distance vs vertical range)
     * 3. Point density (enough data points)
     */
    private fun calculateSimplifiedQualityScore(
        points: List<PathPoint>,
        totalDistance: Float,
        verticalRange: Float
    ): Float {
        try {
            // 1. Completeness Score (based on vertical range)
            // Good reps should have decent vertical movement
            val completenessScore = when {
                verticalRange >= 15f -> 100f  // Excellent range
                verticalRange >= 10f -> 80f   // Good range
                verticalRange >= 5f -> 60f    // Fair range
                else -> 30f                   // Poor range
            }

            // 2. Efficiency Score (path should be reasonably direct)
            // Ratio of vertical range to total distance (higher is better)
            val efficiencyRatio = if (totalDistance > 0) verticalRange / totalDistance else 0f
            val efficiencyScore = when {
                efficiencyRatio >= 0.8f -> 100f  // Very efficient
                efficiencyRatio >= 0.6f -> 80f   // Good efficiency
                efficiencyRatio >= 0.4f -> 60f   // Fair efficiency
                else -> 40f                       // Poor efficiency
            }

            // 3. Data Quality Score (based on point count)
            val dataQualityScore = when {
                points.size >= 20 -> 100f  // Excellent data
                points.size >= 15 -> 85f   // Good data
                points.size >= 10 -> 70f   // Fair data
                else -> 50f                 // Minimal data
            }

            // 4. Consistency Score (check for smooth movement)
            val consistencyScore = calculateMovementConsistency(points)

            // Weighted final score
            val finalScore = (
                    completenessScore * 0.3f +      // 30% - movement range
                            efficiencyScore * 0.3f +        // 30% - path efficiency
                            dataQualityScore * 0.2f +       // 20% - data quality
                            consistencyScore * 0.2f         // 20% - movement consistency
                    ).coerceIn(10f, 100f)

            return finalScore

        } catch (e: Exception) {
            Log.w(TAG, "Error calculating quality score: ${e.message}")
            return 50f // Default score if calculation fails
        }
    }

    /**
     * Calculate movement consistency (smooth vs jerky movement)
     */
    private fun calculateMovementConsistency(points: List<PathPoint>): Float {
        if (points.size < 3) return 50f

        try {
            // Calculate movement smoothness by looking at direction changes
            var directionChanges = 0
            var lastDirection = 0f

            for (i in 1 until points.size) {
                val currentDirection = points[i].y - points[i-1].y

                if (i > 1 && lastDirection != 0f) {
                    // Check if direction changed significantly
                    if ((lastDirection > 0 && currentDirection < -0.01f) ||
                        (lastDirection < 0 && currentDirection > 0.01f)) {
                        directionChanges++
                    }
                }
                lastDirection = currentDirection
            }

            // Fewer direction changes = smoother movement = higher score
            val changeRatio = directionChanges.toFloat() / points.size
            return when {
                changeRatio <= 0.1f -> 100f  // Very smooth
                changeRatio <= 0.2f -> 80f   // Smooth
                changeRatio <= 0.4f -> 60f   // Somewhat jerky
                else -> 40f                  // Very jerky
            }

        } catch (e: Exception) {
            Log.w(TAG, "Error calculating consistency: ${e.message}")
            return 50f
        }
    }
}