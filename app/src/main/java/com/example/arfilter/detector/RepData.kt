package com.example.arfilter.detector

import java.text.SimpleDateFormat
import java.util.*

/**
 * SIMPLIFIED RepData class - Focused on essential metrics only
 * Removed: Avg Velocity, Peak Velocity, Path Deviation, Duration, Eccentric/Pause/Concentric Duration
 */
data class RepData(
    val repNumber: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val exercise: String,
    val tempo: String,

    // ESSENTIAL Movement Quality Metrics (KEPT)
    val totalDistance: Float,           // Total bar path distance
    val verticalRange: Float,           // Pure vertical displacement

    // REMOVED: Complex velocity and timing metrics
    // val avgVelocity: Float,
    // val peakVelocity: Float,
    // val pathDeviation: Float,
    // val duration: Float,
    // val eccentricDuration: Float,
    // val pauseDuration: Float,
    // val concentricDuration: Float,

    // Quality Score (KEPT - most important)
    val qualityScore: Float            // Overall rep quality (0-100)
) {

    fun getFormattedTimestamp(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }

    fun getFormattedDistance(): String {
        return String.format("%.2f", totalDistance)
    }

    fun getQualityGrade(): String {
        return when {
            qualityScore >= 90 -> "A"  // 90%+ = Excellent
            qualityScore >= 70 -> "B"  // 70-89% = Good
            qualityScore >= 50 -> "C"  // 50-69% = Fair
            qualityScore >= 30 -> "D"  // 30-49% = Poor
            else -> "F"                // <30% = Fail
        }
    }

    /**
     * SIMPLIFIED CSV row format - Only essential columns
     */
    fun toCsvRow(): String {
        return listOf(
            repNumber,
            getFormattedTimestamp(),
            exercise,
            tempo,
            String.format("%.2f", totalDistance),
            String.format("%.2f", verticalRange),
            String.format("%.0f", qualityScore),
            getQualityGrade()
        ).joinToString(",")
    }

    companion object {
        /**
         * SIMPLIFIED CSV header - Only essential columns
         */
        fun getCsvHeader(): String {
            return listOf(
                "Rep_Number",
                "Timestamp",
                "Exercise",
                "Tempo",
                "Total_Distance_cm",
                "Vertical_Range_cm",
                "Quality_Score",
                "Grade"
            ).joinToString(",")
        }
    }
}