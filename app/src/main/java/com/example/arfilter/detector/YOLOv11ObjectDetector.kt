// IMPROVED YOLOv11ObjectDetector.kt - More accurate and consistent detection

package com.example.arfilter.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class YOLOv11ObjectDetector(
    context: Context,
    modelPath: String = "optimizefloat16.tflite",
    private val inputSize: Int = 640,
    private val confThreshold: Float = 0.2f, // LOWERED for better detection
    private val iouThreshold: Float = 0.3f,   // LOWERED for better tracking
    private val maxDetections: Int = 3
) {

    private val classLabels = arrayOf("Barbell")
    private val interpreter: Interpreter

    // Model-specific constants
    private val numDetections = 8400
    private val numFeatures = 5

    // Output buffer matching model architecture
    private val outputBuffer = Array(1) { Array(numFeatures) { FloatArray(numDetections) } }

    // TRACKING CONSISTENCY IMPROVEMENTS
    private var lastValidDetection: Detection? = null
    private var consecutiveFailures = 0
    private val maxConsecutiveFailures = 3

    // STABILITY TRACKING
    private val recentDetections = mutableListOf<Detection>()
    private val maxRecentDetections = 5

    companion object {
        private const val TAG = "YOLOv11Detector"
    }

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(4) // INCREASED for better performance
            setUseNNAPI(true)
        }

        interpreter = Interpreter(loadModelFile(context, modelPath), options)
        Log.d(TAG, "YOLOv11 detector initialized with improved settings")
    }

    private fun loadModelFile(context: Context, assetPath: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(assetPath)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        return try {
            val inputBuffer = preprocessImage(bitmap)
            interpreter.run(inputBuffer, outputBuffer)
            val detections = postProcess()

            // IMPROVED: Apply consistency filtering
            val consistentDetections = applyConsistencyFiltering(detections)

            // Update tracking state
            updateTrackingState(consistentDetections)

            consistentDetections

        } catch (e: Exception) {
            Log.e(TAG, "Error during detection: ${e.message}", e)
            handleDetectionFailure()
        }
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        // IMPROVED: Better normalization
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val pixelValue = intValues[i * inputSize + j]

                // Better color normalization
                val r = ((pixelValue shr 16) and 0xFF) / 255.0f
                val g = ((pixelValue shr 8) and 0xFF) / 255.0f
                val b = (pixelValue and 0xFF) / 255.0f

                byteBuffer.putFloat(r)
                byteBuffer.putFloat(g)
                byteBuffer.putFloat(b)
            }
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    private fun postProcess(): List<Detection> {
        val detections = mutableListOf<Detection>()

        try {
            val centerXArray = outputBuffer[0][0]
            val centerYArray = outputBuffer[0][1]
            val widthArray = outputBuffer[0][2]
            val heightArray = outputBuffer[0][3]
            val confidenceArray = outputBuffer[0][4]

            for (i in 0 until numDetections) {
                val confidence = confidenceArray[i]

                if (confidence >= confThreshold) {
                    val centerX = centerXArray[i]
                    val centerY = centerYArray[i]
                    val width = widthArray[i]
                    val height = heightArray[i]

                    val left = (centerX - width / 2f).coerceIn(0f, 1f)
                    val top = (centerY - height / 2f).coerceIn(0f, 1f)
                    val right = (centerX + width / 2f).coerceIn(0f, 1f)
                    val bottom = (centerY + height / 2f).coerceIn(0f, 1f)

                    if (right > left && bottom > top) {
                        val bbox = RectF(left, top, right, bottom)
                        if (isValidBarbellDetection(bbox, confidence)) {
                            detections.add(Detection(bbox, confidence, 0))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in postProcess: ${e.message}", e)
        }

        return applyNMS(detections)
    }

    // IMPROVED: More lenient but smarter validation
    private fun isValidBarbellDetection(bbox: RectF, confidence: Float): Boolean {
        val width = bbox.right - bbox.left
        val height = bbox.bottom - bbox.top
        val aspectRatio = width / height
        val area = width * height

        // RELAXED size constraints for better detection
        val validSize = area > 0.001f && area < 0.5f
        val validAspectRatio = aspectRatio > 0.2f && aspectRatio < 10.0f
        val validDimensions = width > 0.01f && height > 0.005f

        // IMPROVED: Context-aware validation with recent detections
        val contextValid = if (recentDetections.isNotEmpty()) {
            val avgCenter = getAverageCenter()
            val currentCenter = Pair((bbox.left + bbox.right) / 2f, (bbox.top + bbox.bottom) / 2f)
            val distance = calculateDistance(currentCenter, avgCenter)
            distance < 0.2f // Allow more movement
        } else true

        return validSize && validAspectRatio && validDimensions && contextValid
    }

    // IMPROVED: Better consistency filtering
    private fun applyConsistencyFiltering(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) {
            return handleNoDetections()
        }

        // If we have recent detections, prefer those closest to the average
        return if (recentDetections.isNotEmpty()) {
            val avgCenter = getAverageCenter()
            detections.sortedBy { detection ->
                val center = Pair(
                    (detection.bbox.left + detection.bbox.right) / 2f,
                    (detection.bbox.top + detection.bbox.bottom) / 2f
                )
                calculateDistance(center, avgCenter)
            }.take(1) // Take only the closest one for consistency
        } else {
            detections.take(1) // Take the best detection
        }
    }

    private fun handleNoDetections(): List<Detection> {
        consecutiveFailures++

        // IMPROVED: Return interpolated detection if we recently had good detections
        return if (consecutiveFailures < maxConsecutiveFailures && lastValidDetection != null) {
            Log.d(TAG, "Using last valid detection (failures: $consecutiveFailures)")
            listOf(lastValidDetection!!)
        } else {
            emptyList()
        }
    }

    private fun updateTrackingState(detections: List<Detection>) {
        if (detections.isNotEmpty()) {
            consecutiveFailures = 0
            lastValidDetection = detections.first()

            // Update recent detections buffer
            recentDetections.add(detections.first())
            if (recentDetections.size > maxRecentDetections) {
                recentDetections.removeAt(0)
            }
        }
    }

    private fun getAverageCenter(): Pair<Float, Float> {
        if (recentDetections.isEmpty()) return Pair(0.5f, 0.5f)

        val avgX = recentDetections.map { (it.bbox.left + it.bbox.right) / 2f }.average().toFloat()
        val avgY = recentDetections.map { (it.bbox.top + it.bbox.bottom) / 2f }.average().toFloat()

        return Pair(avgX, avgY)
    }

    private fun calculateDistance(point1: Pair<Float, Float>, point2: Pair<Float, Float>): Float {
        val dx = point1.first - point2.first
        val dy = point1.second - point2.second
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun handleDetectionFailure(): List<Detection> {
        consecutiveFailures++

        return if (consecutiveFailures < maxConsecutiveFailures && lastValidDetection != null) {
            listOf(lastValidDetection!!)
        } else {
            emptyList()
        }
    }

    private fun applyNMS(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sortedDetections = detections.sortedByDescending { it.score }.toMutableList()
        val finalDetections = mutableListOf<Detection>()

        while (sortedDetections.isNotEmpty() && finalDetections.size < maxDetections) {
            val bestDetection = sortedDetections.removeAt(0)
            finalDetections.add(bestDetection)

            sortedDetections.removeAll { detection ->
                calculateIoU(bestDetection.bbox, detection.bbox) > iouThreshold
            }
        }

        return finalDetections
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = max(box1.left, box2.left)
        val intersectionTop = max(box1.top, box2.top)
        val intersectionRight = min(box1.right, box2.right)
        val intersectionBottom = min(box1.bottom, box2.bottom)

        val intersectionWidth = max(0f, intersectionRight - intersectionLeft)
        val intersectionHeight = max(0f, intersectionBottom - intersectionTop)
        val intersectionArea = intersectionWidth * intersectionHeight

        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    fun getClassLabel(classId: Int): String {
        return if (classId < classLabels.size) classLabels[classId] else "Unknown"
    }

    fun close() {
        interpreter.close()
        Log.d(TAG, "YOLOv11 detector closed")
    }
}