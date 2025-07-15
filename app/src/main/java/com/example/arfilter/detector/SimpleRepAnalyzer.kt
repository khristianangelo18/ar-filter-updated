package com.example.arfilter.detector

import kotlin.math.*

/**
 * Simple analyzer that converts bar path data into useful rep metrics
 * Focused on practical metrics that lifters care about
 */
class SimpleRepAnalyzer {

    /**
     * Analyze a completed rep and extract useful metrics
     */
    fun analyzeRep(
        barPath: BarPath,
        exercise: String,
        tempo: String,
        repNumber: Int
    ): RepData? {

        val points = barPath.points
        if (points.size < 10) return null // Need minimum points for analysis

        // Convert normalized coordinates to approximate centimeters
        // Assume average screen height represents about 60cm of movement
        val pixelToCm = 60f

        // Basic Movement Metrics
        val totalDistance = calculateTotalDistance(points) * pixelToCm
        val verticalRange = calculateVerticalRange(points) * pixelToCm
        val pathDeviation = calculatePathDeviation(points) * pixelToCm
        val duration = (points.last().timestamp - points.first().timestamp) / 1000f

        // Velocity Calculations
        val velocities = calculateVelocities(points, pixelToCm)
        val avgVelocity = velocities.average().toFloat()
        val peakVelocity = velocities.maxOrNull() ?: 0f

        // Phase Analysis
        val phases = analyzePhases(points)

        // Quality Score (0-100 based on smoothness and consistency)
        val qualityScore = calculateQualityScore(points, pathDeviation, velocities)

        return RepData(
            repNumber = repNumber,
            exercise = exercise,
            tempo = tempo,
            totalDistance = totalDistance,
            verticalRange = verticalRange,
            avgVelocity = avgVelocity,
            peakVelocity = peakVelocity,
            pathDeviation = pathDeviation,
            duration = duration,
            eccentricDuration = phases.eccentric,
            pauseDuration = phases.pause,
            concentricDuration = phases.concentric,
            qualityScore = qualityScore
        )
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

    private fun calculatePathDeviation(points: List<PathPoint>): Float {
        if (points.size < 2) return 0f

        val avgX = points.map { it.x }.average().toFloat()
        val deviations = points.map { abs(it.x - avgX) }
        return deviations.average().toFloat()
    }

    private fun calculateVelocities(points: List<PathPoint>, pixelToCm: Float): List<Float> {
        if (points.size < 2) return emptyList()

        return points.zipWithNext { a, b ->
            val distance = a.distanceTo(b) * pixelToCm
            val timeDiff = (b.timestamp - a.timestamp) / 1000f
            if (timeDiff > 0) distance / timeDiff else 0f
        }
    }

    private data class PhaseBreakdown(
        val eccentric: Float,
        val pause: Float,
        val concentric: Float
    )

    private fun analyzePhases(points: List<PathPoint>): PhaseBreakdown {
        if (points.size < 6) {
            val totalTime = (points.last().timestamp - points.first().timestamp) / 1000f
            return PhaseBreakdown(totalTime * 0.4f, totalTime * 0.2f, totalTime * 0.4f)
        }

        // Find direction changes to identify phases
        val directions = points.zipWithNext { a, b -> b.y - a.y }

        // Simple phase detection based on vertical movement
        var eccentricEnd = directions.indexOfFirst { it > 0 } // Going down stops
        if (eccentricEnd == -1) eccentricEnd = directions.size / 3

        var concentricStart = directions.indexOfLast { it < 0 } // Going up starts
        if (concentricStart == -1) concentricStart = directions.size * 2 / 3

        val eccentricDuration = (points[eccentricEnd].timestamp - points.first().timestamp) / 1000f
        val pauseDuration = if (concentricStart > eccentricEnd) {
            (points[concentricStart].timestamp - points[eccentricEnd].timestamp) / 1000f
        } else 0f
        val concentricDuration = (points.last().timestamp - points[concentricStart].timestamp) / 1000f

        return PhaseBreakdown(
            max(0f, eccentricDuration),
            max(0f, pauseDuration),
            max(0f, concentricDuration)
        )
    }

    private fun calculateQualityScore(
        points: List<PathPoint>,
        pathDeviation: Float,
        velocities: List<Float>
    ): Float {
        // Quality based on:
        // 1. Path consistency (lower deviation = higher score)
        // 2. Velocity smoothness (less erratic = higher score)
        // 3. Movement completeness

        val deviationScore = max(0f, 100f - (pathDeviation * 100f))

        val velocityConsistency = if (velocities.isNotEmpty()) {
            val avgVel = velocities.average()
            val variance = velocities.map { (it - avgVel).pow(2) }.average()
            val smoothnessScore = max(0f, 100f - (variance.toFloat() * 10f))
            smoothnessScore
        } else 50f

        val completenessScore = if (points.size >= 20) 100f else (points.size / 20f) * 100f

        return ((deviationScore * 0.4f) + (velocityConsistency * 0.4f) + (completenessScore * 0.2f))
            .coerceIn(0f, 100f)
    }
}