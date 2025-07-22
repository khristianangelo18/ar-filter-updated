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
 * ENHANCED CSV report manager with better error handling and validation
 */
class CsvReportManager(private val context: Context) {

    companion object {
        private const val TAG = "CsvReportManager"
        private const val FILE_PROVIDER_AUTHORITY = "com.example.arfilter.fileprovider"
    }

    /**
     * ENHANCED: Generate and save CSV report with better validation
     */
    suspend fun generateReport(
        repDataList: List<RepData>,
        sessionInfo: SessionInfo
    ): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "📝 Starting CSV file creation with ${repDataList.size} reps")

            if (repDataList.isEmpty()) {
                Log.w(TAG, "Cannot create report with empty rep data")
                return@withContext null
            }

            val fileName = generateFileName(sessionInfo)
            Log.d(TAG, "📁 Creating file: $fileName")

            val file = createCsvFile(fileName, repDataList, sessionInfo)

            // ENHANCED: Verify file was created successfully
            if (file.exists() && file.length() > 0) {
                Log.d(TAG, "✅ CSV report generated: ${file.absolutePath} (${file.length()} bytes)")

                // Clean up old reports
                cleanupOldReports()

                return@withContext file.absolutePath
            } else {
                Log.e(TAG, "❌ File creation failed or file is empty")
                return@withContext null
            }

        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to generate CSV report", e)
            return@withContext null
        }
    }

    /**
     * ENHANCED: Share function with better error handling
     */
    suspend fun shareReport(filePath: String): Boolean = withContext(Dispatchers.Main) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "❌ File not found: $filePath")
                return@withContext false
            }

            if (file.length() == 0L) {
                Log.e(TAG, "❌ File is empty: $filePath")
                return@withContext false
            }

            Log.d(TAG, "📤 Sharing file: ${file.name} (${file.length()} bytes)")

            val uri = FileProvider.getUriForFile(
                context,
                FILE_PROVIDER_AUTHORITY,
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "PowerLifting Rep Analysis Report")
                putExtra(Intent.EXTRA_TEXT, "Your rep analysis data from PowerLifting AR Coach\n\nGenerated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share Rep Report")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)

            Log.d(TAG, "✅ Share intent launched successfully")
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to share CSV report: ${e.message}", e)
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

            // SIMPLIFIED Summary Statistics
            writer.append("\n")
            writer.append("# SUMMARY STATISTICS\n")
            writer.append("Metric,Value\n")
            writer.append("Total Reps,${repDataList.size}\n")
            writer.append("Average Quality Score,${String.format("%.1f", calculateAverageQuality(repDataList))}\n")
            writer.append("Best Rep Quality,${repDataList.maxOfOrNull { it.qualityScore } ?: 0f}\n")
            writer.append("Average Total Distance,${String.format("%.1f", repDataList.map { it.totalDistance }.average())} cm\n")
            writer.append("Average Vertical Range,${String.format("%.1f", repDataList.map { it.verticalRange }.average())} cm\n")

            // Quality Distribution
            writer.append("\n")
            writer.append("# QUALITY DISTRIBUTION\n")
            writer.append("Grade,Count,Percentage\n")
            val excellentReps = repDataList.count { it.qualityScore >= 90 }      // A: 90%+
            val goodReps = repDataList.count { it.qualityScore >= 70 && it.qualityScore < 90 }  // B: 70-89%
            val fairReps = repDataList.count { it.qualityScore >= 50 && it.qualityScore < 70 }  // C: 50-69%
            val poorReps = repDataList.count { it.qualityScore >= 30 && it.qualityScore < 50 }  // D: 30-49%
            val failReps = repDataList.count { it.qualityScore < 30 }           // F: <30%
            val total = repDataList.size.toFloat()

            writer.append("A (90%+),${excellentReps},${String.format("%.1f", (excellentReps/total)*100)}%\n")
            writer.append("B (70-89%),${goodReps},${String.format("%.1f", (goodReps/total)*100)}%\n")
            writer.append("C (50-69%),${fairReps},${String.format("%.1f", (fairReps/total)*100)}%\n")
            writer.append("D (30-49%),${poorReps},${String.format("%.1f", (poorReps/total)*100)}%\n")
            writer.append("F (<30%),${failReps},${String.format("%.1f", (failReps/total)*100)}%\n")
        }

        return file
    }

    private fun generateFileName(sessionInfo: SessionInfo): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        val exercise = sessionInfo.exercise.replace(" ", "_").replace("-", "_")
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
     * ENHANCED: Clean up old reports (keep only last 10)
     */
    fun cleanupOldReports() {
        try {
            val reports = getSavedReports()
            if (reports.size > 10) {
                reports.drop(10).forEach { file ->
                    try {
                        if (file.delete()) {
                            Log.d(TAG, "🗑️ Deleted old report: ${file.name}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete old report: ${file.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}", e)
        }
    }

    /**
     * ENHANCED: Get report statistics
     */
    fun getReportStats(): ReportStats {
        val reports = getSavedReports()
        val totalSize = reports.sumOf { it.length() }
        val oldestReport = reports.minByOrNull { it.lastModified() }
        val newestReport = reports.maxByOrNull { it.lastModified() }

        return ReportStats(
            totalReports = reports.size,
            totalSizeBytes = totalSize,
            oldestReportDate = oldestReport?.lastModified(),
            newestReportDate = newestReport?.lastModified()
        )
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

/**
 * ENHANCED: Report statistics data class
 */
data class ReportStats(
    val totalReports: Int,
    val totalSizeBytes: Long,
    val oldestReportDate: Long?,
    val newestReportDate: Long?
) {
    fun getTotalSizeMB(): Float = totalSizeBytes / (1024f * 1024f)
}