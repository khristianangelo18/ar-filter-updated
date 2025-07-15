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

/**
 * Simplified YOLOv11 Object Detector for Barbell Detection in ARFilter
 * Optimized for background operation without interfering with AR overlay
 */
class YOLOv11ObjectDetector(
    context: Context,
    modelPath: String = "optimizefloat16.tflite",
    private val inputSize: Int = 640,
    private val confThreshold: Float = 0.3f,
    private val iouThreshold: Float = 0.45f,
    private val maxDetections: Int = 5
) {

    private val classLabels = arrayOf("Barbell")
    private val interpreter: Interpreter

    // Model-specific constants
    private val numDetections = 8400 // From output shape (1, 5, 8400)
    private val numFeatures = 5      // [x, y, w, h, confidence]

    // Output buffer matching model architecture
    private val outputBuffer = Array(1) { Array(numFeatures) { FloatArray(numDetections) } }

    companion object {
        private const val TAG = "YOLOv11Detector"
    }

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(2) // Limited threads for background operation
            setUseNNAPI(true)
        }

        interpreter = Interpreter(loadModelFile(context, modelPath), options)
        Log.d(TAG, "YOLOv11 detector initialized for background barbell detection")
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
            postProcess()
        } catch (e: Exception) {
            Log.e(TAG, "Error during detection: ${e.message}", e)
            emptyList()
        }
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val pixelValue = intValues[i * inputSize + j]

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
                        if (isValidBarbellDetection(bbox)) {
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

    private fun isValidBarbellDetection(bbox: RectF): Boolean {
        val width = bbox.right - bbox.left
        val height = bbox.bottom - bbox.top
        val aspectRatio = width / height
        val area = width * height

        return aspectRatio > 0.5f && aspectRatio < 8.0f &&
                area > 0.001f && area < 0.4f &&
                width > 0.02f && height > 0.01f
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
