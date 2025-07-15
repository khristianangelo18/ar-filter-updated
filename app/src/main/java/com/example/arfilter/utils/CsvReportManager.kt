package com.example.arfilter.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.arfilter.detector.RepData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Simple CSV report manager for rep analysis data
 * Generates downloadable CSV files with rep metrics
 */
class CsvReportManager(private val context: Context) {

    companion object {
        private const val TAG = "CsvReportManager"
        private const val FILE_PROVIDER_AUTHORITY = "com.example.arfilter.fileprovider"
    }

    /**
     * Generate and save CSV report from rep data
     */
    suspend fun generateReport(
        repDataList: List<RepData>,
        sessionInfo: SessionInfo
    ): String? = withContext(Dispatchers.IO) {
        try {
            val fileName = generateFileName(sessionInfo)
            val file = createCsvFile(fileName, repDataList, sessionInfo)

            Log.d(TAG, "CSV report generated: ${file.absolutePath}")
            return@withContext file.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate CSV report", e)
            return@withContext null
        }
    }

    /**
     * Share the generated CSV file
     */
    suspend fun shareReport(filePath: String): Boolean = withContext(Dispatchers.Main) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "File not found: $filePath")
                return@withContext false
            }

            val uri = FileProvider.getUriForFile(
                context,
                FILE_PROVIDER_AUTHORITY,
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "PowerLifting Rep Analysis Report")
                putExtra(Intent.EXTRA_TEXT, "Your rep analysis data from PowerLifting AR Coach")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share Rep Report")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)

            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to share CSV report", e)
            return@withContext false
        }
    }

    private fun createCsvFile(
        fileName: String,
        repDataList: List<RepData>,
        sessionInfo: SessionInfo
    ): File {
        val downloadsDir = File(context.getExternalFilesDir(null), "PowerLifting_Reports")
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        val file = File(downloadsDir, fileName)

        FileWriter(file).use { writer ->
            // Session Header
            writer.append("# PowerLifting AR Coach - Rep Analysis Report\n")
            writer.append("# Generated: ${sessionInfo.timestamp}\n")
            writer.append("# Exercise: ${sessionInfo.exercise}\n")
            writer.append("# Tempo: ${sessionInfo.tempo}\n")
            writer.append("# Total Reps: ${repDataList.size}\n")
            writer.append("# Session Duration: ${sessionInfo.duration}\n")
            writer.append("# Average Quality Score: ${calculateAverageQuality(repDataList)}\n")
            writer.append("\n")

            // CSV Header
            writer.append(RepData.getCsvHeader())
            writer.append("\n")

            // Rep Data
            repDataList.forEach { repData ->
                writer.append(repData.toCsvRow())
                writer.append("\n")
            }

            // Summary Statistics
            writer.append("\n")
            writer.append("# SUMMARY STATISTICS\n")
            writer.append("Metric,Value\n")
            writer.append("Total Reps,${repDataList.size}\n")
            writer.append("Average Quality Score,${String.format("%.1f", calculateAverageQuality(repDataList))}\n")
            writer.append("Best Rep Quality,${repDataList.maxOfOrNull { it.qualityScore } ?: 0f}\n")
            writer.append("Average Duration,${String.format("%.1f", repDataList.map { it.duration }.average())} sec\n")
            writer.append("Average Vertical Range,${String.format("%.1f", repDataList.map { it.verticalRange }.average())} cm\n")
            writer.append("Average Path Deviation,${String.format("%.2f", repDataList.map { it.pathDeviation }.average())} cm\n")
        }

        return file
    }

    private fun generateFileName(sessionInfo: SessionInfo): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        val exercise = sessionInfo.exercise.replace(" ", "_")
        return "PowerLifting_${exercise}_${timestamp}.csv"
    }

    private fun calculateAverageQuality(repDataList: List<RepData>): Float {
        return if (repDataList.isNotEmpty()) {
            repDataList.map { it.qualityScore }.average().toFloat()
        } else 0f
    }

    /**
     * Get all saved reports
     */
    fun getSavedReports(): List<File> {
        val reportsDir = File(context.getExternalFilesDir(null), "PowerLifting_Reports")
        return if (reportsDir.exists()) {
            reportsDir.listFiles()?.filter { it.extension == "csv" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * Delete old reports (keep only last 10)
     */
    fun cleanupOldReports() {
        val reports = getSavedReports()
        if (reports.size > 10) {
            reports.drop(10).forEach { file ->
                try {
                    file.delete()
                    Log.d(TAG, "Deleted old report: ${file.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete old report: ${file.name}", e)
                }
            }
        }
    }
}

/**
 * Session information for the report header
 */
data class SessionInfo(
    val exercise: String,
    val tempo: String,
    val timestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
    val duration: String
)