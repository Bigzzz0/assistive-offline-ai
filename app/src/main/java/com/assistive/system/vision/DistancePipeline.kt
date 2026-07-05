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
    val depthEstimator: DepthEstimator,
    private val onDistanceUpdate: (List<DistanceResult>) -> Unit
) {
    var lastDistanceResults: List<DistanceResult> = emptyList()
        private set
    private val TAG = "DistancePipeline"

    // Exponential Moving Average (EMA) filters for temporal smoothing (stability)
    var emaCenterDistance = -1f
        private set
    private val objectEmaMap = mutableMapOf<String, Float>()
    private val objectMissedCounts = mutableMapOf<String, Int>()
    private val EMA_ALPHA = 0.20f // 20% weight to new value, 80% to old value for smooth transition

    // Alert thresholds
    companion object {
        const val THRESHOLD_DANGER  = 0.8f   // meters — immediate vibration + speech
        const val THRESHOLD_WARNING = 1.5f   // meters — warning vibration + speech every 2s
        const val THRESHOLD_NEAR    = 3.0f   // meters — gentle haptic only
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var processingJob: Job? = null
    private var isRunning = false

    // Pre-allocated static frame buffer for zero-allocation copy
    private var pipelineBitmap: Bitmap? = null
    private var pipelineCanvas: android.graphics.Canvas? = null
    private val bitmapLock = Any()
    private var hasNewFrame = false

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Submit the latest camera frame for processing.
     * Copies the pixel data instantly under a <1ms lock so caller can recycle the source.
     */
    fun submitFrame(bitmap: Bitmap) {
        synchronized(bitmapLock) {
            val target = pipelineBitmap ?: Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config).also {
                pipelineBitmap = it
                pipelineCanvas = android.graphics.Canvas(it)
            }
            pipelineCanvas?.drawBitmap(bitmap, 0f, 0f, null)
            hasNewFrame = true
        }
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

                    val results = if (objectDetector == null) {
                        depthOnlyFallback()
                    } else {
                        synchronized(bitmapLock) {
                            val bitmap = pipelineBitmap
                            if (bitmap != null && hasNewFrame) {
                                hasNewFrame = false
                                processFrame(bitmap)
                            } else {
                                null
                            }
                        }
                    }

                    if (results != null && results.isNotEmpty()) {
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
        synchronized(bitmapLock) {
            pipelineBitmap?.recycle()
            pipelineBitmap = null
            pipelineCanvas = null
        }
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
        // 1. Run object detection using the synchronized drawBitmap lock inside ObjectDetector
        val detected = objectDetector?.detect(bitmap, bitmapLock) ?: emptyList()

        // NOTE: depthEstimator.updateFrame() is called by the main loop before processFrame()

        // If no objects detected, run depth-only mode
        if (detected.isEmpty()) {
            return depthOnlyFallback()
        }

        // Track which labels are detected in this frame
        val detectedLabels = detected.map { it.label }.toSet()
        
        // Increment missed counts for any objects that were in our map but not detected in this frame
        val missingLabels = objectEmaMap.keys.filter { it !in detectedLabels }
        for (label in missingLabels) {
            val count = (objectMissedCounts[label] ?: 0) + 1
            objectMissedCounts[label] = count
            if (count > 5) { // If missing for ~1 second (5 ticks * 200ms), remove from history
                objectEmaMap.remove(label)
                objectMissedCounts.remove(label)
            }
        }

        // Batch coordinate query: Gather all points to query from DepthEstimator in a single batch
        val pointsToQuery = mutableListOf<Pair<Float, Float>>()
        
        // Index 0: Center screen query
        pointsToQuery.add(0.5f to 0.5f)
        
        // Indices 1 + 5*i to 5 + 5*i: 5-point cross pattern for each detected object
        for (obj in detected) {
            val box = obj.boundingBox
            val cx = (box.left + box.right) / 2f
            val cy = (box.top + box.bottom) / 2f
            val w = box.width()
            val h = box.height()
            
            pointsToQuery.add(cx to cy)
            pointsToQuery.add((cx - w / 4f).coerceIn(0.01f, 0.99f) to cy)
            pointsToQuery.add((cx + w / 4f).coerceIn(0.01f, 0.99f) to cy)
            pointsToQuery.add(cx to (cy - h / 4f).coerceIn(0.01f, 0.99f))
            pointsToQuery.add(cx to (cy + h / 4f).coerceIn(0.01f, 0.99f))
        }

        // Query all depth values in a single synchronous batch call on the GL thread
        val allDepths = depthEstimator.getDepthAtPoints(pointsToQuery)

        // Process object detection results
        val results = mutableListOf<DistanceResult>()
        for (i in detected.indices) {
            val obj = detected[i]
            val startIdx = 1 + 5 * i
            
            // Extract the 5 depth values queried for this object
            val objectDepths = allDepths.subList(startIdx, startIdx + 5).filter { it > 0f }
            val closestDepth = if (objectDepths.isNotEmpty()) objectDepths.minOrNull() ?: -1f else -1f
            
            if (closestDepth > 0f) {
                // Reset missed count
                objectMissedCounts[obj.label] = 0
                
                // Apply EMA smoothing
                val oldEma = objectEmaMap[obj.label]
                val smoothedDepth = if (oldEma == null || oldEma <= 0f) {
                    closestDepth
                } else {
                    (EMA_ALPHA * closestDepth) + ((1f - EMA_ALPHA) * oldEma)
                }
                objectEmaMap[obj.label] = smoothedDepth

                results.add(DistanceResult(
                    label = obj.label, labelThai = obj.labelThai,
                    distanceMeters = smoothedDepth, confidence = obj.confidence,
                    boundingBox = obj.boundingBox, isDepthReal = true
                ))
            }
        }

        // Extract center screen depth (Index 0)
        val centerDepth = allDepths[0]
        val smoothedCenterDepth = if (centerDepth > 0f) {
            val oldCenterEma = emaCenterDistance
            val smoothed = if (oldCenterEma <= 0f) {
                centerDepth
            } else {
                (EMA_ALPHA * centerDepth) + ((1f - EMA_ALPHA) * oldCenterEma)
            }
            emaCenterDistance = smoothed
            smoothed
        } else {
            emaCenterDistance = -1f // Reset center EMA if invalid
            -1f
        }

        if (smoothedCenterDepth > 0f) {
            val coversCenter = results.any { it.boundingBox.contains(0.5f, 0.5f) }
            if (!coversCenter) {
                results.add(DistanceResult(
                    label = "center", labelThai = "ตรงกลางภาพ",
                    distanceMeters = smoothedCenterDepth, confidence = 1.0f,
                    boundingBox = android.graphics.RectF(0.45f, 0.45f, 0.55f, 0.55f),
                    isDepthReal = true
                ))
            }
        }

        results.sortBy { it.distanceMeters }
        Log.d(TAG, "Distance results: ${results.joinToString { "${it.labelThai} ${String.format("%.1f", it.distanceMeters)}m" }}")
        lastDistanceResults = results
        return results
    }

    /**
     * Depth-only fallback: no object detection model needed.
     * Samples depth at 5 points across the frame center row in a single batch query.
     */
    private fun depthOnlyFallback(): List<DistanceResult> {
        // Sample points: left-edge, left-center, center, right-center, right-edge
        val samplePoints = listOf(0.1f to 0.5f, 0.3f to 0.5f, 0.5f to 0.5f, 0.7f to 0.5f, 0.9f to 0.5f)
        val depths = depthEstimator.getDepthAtPoints(samplePoints)
        
        var minDepth = Float.MAX_VALUE
        var isReal = false

        for (d in depths) {
            if (d > 0f && d < minDepth) {
                minDepth = d
                isReal = true
            }
        }

        if (minDepth == Float.MAX_VALUE || minDepth <= 0f) {
            emaCenterDistance = -1f
            lastDistanceResults = emptyList()
            return emptyList()
        }

        // Apply EMA to center fallback distance as well
        val smoothed = if (emaCenterDistance <= 0f) {
            minDepth
        } else {
            (EMA_ALPHA * minDepth) + ((1f - EMA_ALPHA) * emaCenterDistance)
        }
        emaCenterDistance = smoothed

        val resultsList = listOf(DistanceResult(
            label = "obstacle", labelThai = "สิ่งกีดขวาง",
            distanceMeters = smoothed, confidence = 1.0f,
            boundingBox = android.graphics.RectF(0.3f, 0.3f, 0.7f, 0.7f),
            isDepthReal = isReal
        ))
        lastDistanceResults = resultsList
        return resultsList
    }
}
