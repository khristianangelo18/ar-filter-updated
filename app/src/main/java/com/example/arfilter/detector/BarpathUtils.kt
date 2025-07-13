package com.example.arfilter.detector

import androidx.compose.ui.graphics.Color
import kotlin.math.*

/**
 * Bar path tracking utilities for ARFilter integration
 * Simplified version focused on basic path tracking without complex analysis
 */

data class PathPoint(
    val x: Float,
    val y: Float,
    val timestamp: Long
) {
    fun distanceTo(other: PathPoint): Float {
        return sqrt((x - other.x).pow(2) + (y - other.y).pow(2))
    }
}

data class BarPath(
    val id: String = generatePathId(),
    val points: MutableList<PathPoint> = mutableListOf(),
    val isActive: Boolean = true,
    val color: Color = Color.Cyan,
    val startTime: Long = System.currentTimeMillis()
) {
    companion object {
        private var pathCounter = 0
        fun generatePathId(): String = "path_${++pathCounter}"
    }

    fun addPoint(point: PathPoint, maxPoints: Int = 200) {
        points.add(point)
        if (points.size > maxPoints) {
            val keepCount = (maxPoints * 0.8).toInt()
            val trimmedPoints = points.takeLast(keepCount)
            points.clear()
            points.addAll(trimmedPoints)
        }
    }

    fun getTotalDistance(): Float {
        if (points.size < 2) return 0f
        return points.zipWithNext { a, b -> a.distanceTo(b) }.sum()
    }

    fun getVerticalRange(): Float {
        if (points.isEmpty()) return 0f
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        return maxY - minY
    }

    fun getDuration(): Long {
        if (points.isEmpty()) return 0L
        return points.last().timestamp - points.first().timestamp
    }
}

enum class MovementDirection {
    UP, DOWN, STABLE
}

data class MovementAnalysis(
    val direction: MovementDirection,
    val velocity: Float,
    val acceleration: Float = 0f,
    val totalDistance: Float,
    val repCount: Int,
    val averageBarSpeed: Float = 0f,
    val peakVelocity: Float = 0f,
    val pathQuality: Float = 0f
)