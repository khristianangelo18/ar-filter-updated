package com.example.arfilter.detector

import java.text.SimpleDateFormat
import java.util.*

/**
 * Simple data class to track individual rep performance
 * Focused on basic metrics that lifters actually care about
 */
data class RepData(
    val repNumber: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val exercise: String,
    val tempo: String,

    // Movement Quality Metrics
    val totalDistance: Float,           // Total bar path distance
    val verticalRange: Float,           // Pure vertical displacement
    val avgVelocity: Float,             // Average movement speed
    val peakVelocity: Float,           // Fastest point in lift
    val pathDeviation: Float,          // How much bar drifted from vertical
    val duration: Float,               // Total rep duration in seconds

    // Phase Breakdown
    val eccentricDuration: Float,      // Time going down
    val pauseDuration: Float,          // Time at bottom
    val concentricDuration: Float,     // Time going up

    // Quality Score (0-100)
    val qualityScore: Float            // Overall rep quality
) {

    fun getFormattedTimestamp(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }

    fun getFormattedDuration(): String {
        return String.format("%.1f", duration)
    }

    fun getFormattedDistance(): String {
        return String.format("%.2f", totalDistance)
    }

    fun getQualityGrade(): String {
        return when {
            qualityScore >= 90 -> "A"
            qualityScore >= 80 -> "B"
            qualityScore >= 70 -> "C"
            qualityScore >= 60 -> "D"
            else -> "F"
        }
    }

    /**
     * Convert to CSV row format
     */
    fun toCsvRow(): String {
        return listOf(
            repNumber,
            getFormattedTimestamp(),
            exercise,
            tempo,
            String.format("%.2f", totalDistance),
            String.format("%.2f", verticalRange),
            String.format("%.2f", avgVelocity),
            String.format("%.2f", peakVelocity),
            String.format("%.2f", pathDeviation),
            getFormattedDuration(),
            String.format("%.1f", eccentricDuration),
            String.format("%.1f", pauseDuration),
            String.format("%.1f", concentricDuration),
            String.format("%.0f", qualityScore),
            getQualityGrade()
        ).joinToString(",")
    }

    companion object {
        /**
         * CSV header row
         */
        fun getCsvHeader(): String {
            return listOf(
                "Rep_Number",
                "Timestamp",
                "Exercise",
                "Tempo",
                "Total_Distance_cm",
                "Vertical_Range_cm",
                "Avg_Velocity_cm_s",
                "Peak_Velocity_cm_s",
                "Path_Deviation_cm",
                "Duration_sec",
                "Eccentric_Duration_sec",
                "Pause_Duration_sec",
                "Concentric_Duration_sec",
                "Quality_Score",
                "Grade"
            ).joinToString(",")
        }
    }
}