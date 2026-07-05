package com.assistive.system.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.assistive.system.logging.AppLogger as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Result of a combined Object Detection + Depth Estimation pass.
 */
data class DistanceResult(
    val label: String,       // English label
    val labelThai: String,   // Thai label for TTS
    val distanceMeters: Float,
    val confidence: Float,
    val boundingBox: RectF,  // normalized [0..1]
    val isDepthReal: Boolean // true = ARCore depth, false = heuristic estimate
)

enum class AlertLevel { DANGER, WARNING, NEAR, CLEAR }

/**
 * DistancePipeline coordinates Object Detection + Depth Estimation to produce
 * real-time distance measurements for detected objects.
 *
 * Architecture:
 *   Camera Frame (Bitmap) ──► ObjectDetector ──► DetectedObject list
 *                                                        │
 *   ARCore Frame ──────────► DepthEstimator ─────────────┘
 *                                                        │
 *                                                 DistanceResult list
 *                                                        │
 *                                              onDistanceUpdate callback
 *
 * Runs at 5 FPS (200ms interval) on a dedicated IO coroutine.
 */
class DistancePipeline(
    private val context: Context,
    private val objectDetector: ObjectDetector?,    // null = depth-only fallback mode
    private val depthEstimator: DepthEstimator,
    private val onDistanceUpdate: (List<DistanceResult>) -> Unit
) {
    private val TAG = "DistancePipeline"

    // Alert thresholds
    companion object {
        const val THRESHOLD_DANGER  = 0.8f   // meters — immediate vibration + speech
        const val THRESHOLD_WARNING = 1.5f   // meters — warning vibration + speech every 2s
        const val THRESHOLD_NEAR    = 3.0f   // meters — gentle haptic only
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var processingJob: Job? = null
    private var latestBitmap: Bitmap? = null
    private var isRunning = false

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Submit the latest camera frame for processing.
     * Thread-safe; called from the camera frame callback.
     */
    fun submitFrame(bitmap: Bitmap) {
        latestBitmap = bitmap
    }

    /**
     * Start the real-time processing loop at ~5 FPS (200ms interval).
     * Runs continuously — no button press needed.
     *  - ARCore depth is ticked every loop iteration
     *  - Object detection runs when a camera bitmap is available
     *  - Depth-only sampling runs even without a bitmap (ARCore only)
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        processingJob = scope.launch {
            Log.i(TAG, "DistancePipeline started — continuous mode")
            while (isActive) {
                try {
                    // Always tick ARCore to get the latest depth frame
                    depthEstimator.updateFrame()

                    val bitmap = latestBitmap
                    val results = if (bitmap != null && !bitmap.isRecycled) {
                        processFrame(bitmap)
                    } else {
                        // No camera bitmap yet — still run depth-only sampling
                        depthOnlyFallback()
                    }

                    if (results.isNotEmpty()) {
                        onDistanceUpdate(results)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Frame processing error: ${e.message}", e)
                }
                delay(200L) // 5 FPS
            }
        }
    }

    /**
     * Stop the processing loop.
     */
    fun stop() {
        processingJob?.cancel()
        processingJob = null
        isRunning = false
        Log.i(TAG, "DistancePipeline stopped")
    }

    /**
     * Classify the alert level for a distance result.
     */
    fun getAlertLevel(distanceMeters: Float): AlertLevel = when {
        distanceMeters in 0.01f..THRESHOLD_DANGER  -> AlertLevel.DANGER
        distanceMeters in 0.01f..THRESHOLD_WARNING -> AlertLevel.WARNING
        distanceMeters in 0.01f..THRESHOLD_NEAR    -> AlertLevel.NEAR
        else                                        -> AlertLevel.CLEAR
    }

    fun release() {
        stop()
        scope.cancel()
        Log.i(TAG, "DistancePipeline released")
    }

    // ─── Private processing ──────────────────────────────────────────────────

    private fun processFrame(bitmap: Bitmap): List<DistanceResult> {
        // 1. Run object detection (may return empty if model not loaded)
        val detected = objectDetector?.detect(bitmap) ?: emptyList()

        // NOTE: depthEstimator.updateFrame() is called by the main loop before processFrame()

        // If no objects detected, run depth-only mode
        if (detected.isEmpty()) {
            return depthOnlyFallback()
        }

        // 3. For each detected object, get depth at bounding box center
        val results = mutableListOf<DistanceResult>()
        for (obj in detected) {
            val arcoreDepth = depthEstimator.getDepthAtBox(obj.boundingBox)
            val (distanceM, isReal) = when {
                arcoreDepth > 0f -> Pair(arcoreDepth, true)
                else -> Pair(depthEstimator.estimateDistanceHeuristic(obj.boundingBox, obj.label), false)
            }
            results.add(DistanceResult(
                label = obj.label, labelThai = obj.labelThai,
                distanceMeters = distanceM, confidence = obj.confidence,
                boundingBox = obj.boundingBox, isDepthReal = isReal
            ))
        }

        results.sortBy { it.distanceMeters }
        Log.d(TAG, "Distance results: ${results.joinToString { "${it.labelThai} ${String.format("%.1f", it.distanceMeters)}m" }}")
        return results
    }

    /**
     * Depth-only fallback: no object detection model needed.
     * Samples depth at 5 points across the frame center row and reports the closest one.
     * Used when efficientdet_lite0.tflite is not available.
     */
    private fun depthOnlyFallback(): List<DistanceResult> {
        // Sample points: left-edge, left-center, center, right-center, right-edge
        val samplePoints = listOf(0.1f to 0.5f, 0.3f to 0.5f, 0.5f to 0.5f, 0.7f to 0.5f, 0.9f to 0.5f)
        var minDepth = Float.MAX_VALUE
        var isReal = false

        for ((nx, ny) in samplePoints) {
            val d = depthEstimator.getDepthAtNormalizedPoint(nx, ny)
            if (d > 0f && d < minDepth) {
                minDepth = d
                isReal = true
            }
        }

        if (minDepth == Float.MAX_VALUE || minDepth <= 0f) return emptyList()

        return listOf(DistanceResult(
            label = "obstacle", labelThai = "สิ่งกีดขวาง",
            distanceMeters = minDepth, confidence = 1.0f,
            boundingBox = android.graphics.RectF(0.3f, 0.3f, 0.7f, 0.7f),
            isDepthReal = isReal
        ))
    }
}
