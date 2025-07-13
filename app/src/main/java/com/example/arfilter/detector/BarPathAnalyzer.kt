package com.example.arfilter.detector

import kotlin.math.abs

/**
 * Simplified bar path analyzer for ARFilter background detection
 * Focuses on basic movement analysis without complex metrics
 */
class BarPathAnalyzer {

    fun analyzeMovement(points: List<PathPoint>): MovementAnalysis? {
        if (points.size < 5) return null

        val recentPoints = points.takeLast(10)
        val totalVerticalMovement = recentPoints.zipWithNext { a, b -> b.y - a.y }.sum()
        val totalTime = recentPoints.last().timestamp - recentPoints.first().timestamp

        val velocity = if (totalTime > 0) abs(totalVerticalMovement) / (totalTime / 1000f) else 0f

        val direction = when {
            totalVerticalMovement > 0.02f -> MovementDirection.DOWN
            totalVerticalMovement < -0.02f -> MovementDirection.UP
            else -> MovementDirection.STABLE
        }

        val totalDistance = points.zipWithNext { a, b -> a.distanceTo(b) }.sum()
        val repCount = countReps(points)

        return MovementAnalysis(
            direction = direction,
            velocity = velocity,
            totalDistance = totalDistance,
            repCount = repCount
        )
    }

    private fun countReps(points: List<PathPoint>): Int {
        if (points.size < 20) return 0

        var repCount = 0
        var lastDirection: MovementDirection? = null
        var inUpPhase = false
        val smoothingWindow = 5
        val repThreshold = 0.05f

        for (i in smoothingWindow until points.size - smoothingWindow) {
            val beforeY = points.subList(i - smoothingWindow, i).map { it.y }.average().toFloat()
            val afterY = points.subList(i, i + smoothingWindow).map { it.y }.average().toFloat()

            val currentDirection = when {
                afterY - beforeY > 0.02f -> MovementDirection.DOWN
                afterY - beforeY < -0.02f -> MovementDirection.UP
                else -> MovementDirection.STABLE
            }

            if (lastDirection == MovementDirection.UP && currentDirection == MovementDirection.DOWN && inUpPhase) {
                val upStartIndex = findLastDirectionChange(points, i, MovementDirection.DOWN, MovementDirection.UP, smoothingWindow)
                if (upStartIndex != -1) {
                    val displacement = abs(points[i].y - points[upStartIndex].y)
                    if (displacement > repThreshold) {
                        repCount++
                        inUpPhase = false
                    }
                }
            }

            if (lastDirection == MovementDirection.DOWN && currentDirection == MovementDirection.UP) {
                inUpPhase = true
            }

            if (currentDirection != MovementDirection.STABLE) {
                lastDirection = currentDirection
            }
        }

        return repCount
    }

    private fun findLastDirectionChange(
        points: List<PathPoint>,
        currentIndex: Int,
        fromDirection: MovementDirection,
        toDirection: MovementDirection,
        smoothingWindow: Int
    ): Int {
        for (i in currentIndex - 1 downTo smoothingWindow) {
            val beforeY = points.subList(i - smoothingWindow, i).map { it.y }.average().toFloat()
            val afterY = points.subList(i, i + smoothingWindow).map { it.y }.average().toFloat()

            val direction = when {
                afterY - beforeY > 0.02f -> MovementDirection.DOWN
                afterY - beforeY < -0.02f -> MovementDirection.UP
                else -> MovementDirection.STABLE
            }

            if (direction == fromDirection) {
                return i
            }
        }
        return -1
    }
}
