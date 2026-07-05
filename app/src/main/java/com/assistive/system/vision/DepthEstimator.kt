package com.assistive.system.vision

import android.app.Activity
import android.content.Context
import android.graphics.RectF
import com.assistive.system.logging.AppLogger as Log
import kotlin.math.abs
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Coordinates2d

/**
 * DepthEstimator provides real-time distance measurements (in meters) to obstacles
 * and the ground/wall in front of the user using native Google ARCore Depth API.
 */
class DepthEstimator {

    private val TAG = "DepthEstimator"

    private var isInitialized = false

    // Real ARCore Session state
    private var session: Session? = null
    var isRealArCoreActive = false
        private set

    var arCoreErrorMessage: String? = null
        private set

    private var depthWidth = 0
    private var depthHeight = 0
    private var depthRowStride = 0
    private var depthPixelStride = 0
    
    @Volatile
    private var depthBuffer: java.nio.ShortBuffer? = null

    // Thread-safe depth request queue for GL thread processing
    data class DepthRequest(
        val nx: Float,
        val ny: Float,
        val callback: (Float) -> Unit
    )

    private val pendingDepthRequests = java.util.concurrent.ConcurrentLinkedQueue<DepthRequest>()

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Checks if ARCore is supported and installed on this device.
     */
    fun checkAndInstallArCore(activity: Activity): Boolean {
        return try {
            val availability = ArCoreApk.getInstance().checkAvailability(activity)
            if (!availability.isSupported) {
                arCoreErrorMessage = "อุปกรณ์ไม่รองรับ ARCore (${availability.name})"
                Log.i(TAG, "ARCore not supported: $arCoreErrorMessage")
                return false
            }
            val installStatus = ArCoreApk.getInstance().requestInstall(activity, true)
            if (installStatus == ArCoreApk.InstallStatus.INSTALLED) {
                true
            } else {
                arCoreErrorMessage = "ยังไม่ได้ติดตั้งหรือต้องการการอัปเดต Google Play Services for AR"
                false
            }
        } catch (e: Exception) {
            arCoreErrorMessage = "ตรวจสอบการติดตั้ง ARCore ล้มเหลว: ${e.message}"
            Log.e(TAG, "ARCore availability check failed: ${e.message}", e)
            false
        }
    }

    /**
     * Initialize native ARCore Session.
     */
    fun initialize(activity: Activity) {
        if (isInitialized) return
        
        try {
            val session = Session(activity)
            val config = Config(session)
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
                config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                config.focusMode = Config.FocusMode.AUTO
                Log.i(TAG, "ARCore Depth API enabled in Session Configuration")
            } else {
                arCoreErrorMessage = "อุปกรณ์นี้ไม่รองรับ Depth API (Automatic Depth Mode)"
                Log.w(TAG, arCoreErrorMessage!!)
            }
            session.configure(config)
            this.session = session
            this.isRealArCoreActive = true
            isInitialized = true
            Log.i(TAG, "Native ARCore session initialized successfully.")
        } catch (e: Exception) {
            arCoreErrorMessage = "ไม่สามารถสร้าง ARCore Session ได้: ${e.message}"
            Log.e(TAG, "Failed to create native ARCore session", e)
            this.session = null
            this.isRealArCoreActive = false
        }
    }

    /**
     * No-op since frame updates are submitted directly via updateDepthBuffer.
     */
    fun updateFrame() {
    }

    /**
     * Copy the current depth image contents to a thread-safe short buffer.
     * Invoked from the OpenGL rendering thread to prevent native leaks.
     */
    fun updateDepthBuffer(
        frame: Frame,
        buffer: java.nio.ShortBuffer?,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ) {
        synchronized(this) {
            depthWidth = width
            depthHeight = height
            depthRowStride = rowStride
            depthPixelStride = pixelStride
            depthBuffer = buffer
        }
    }

    /**
     * Submits a coordinate transform and depth query to be processed on the GL thread.
     * Safely blocks the calling thread for up to 50ms.
     */
    fun getDepthAtNormalizedPoint(nx: Float, ny: Float): Float {
        if (!isRealArCoreActive) return -1f

        val latch = java.util.concurrent.CountDownLatch(1)
        var result = -1f

        pendingDepthRequests.add(DepthRequest(nx, ny) { depth ->
            result = depth
            latch.countDown()
        })

        try {
            latch.await(50L, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Depth query latch timeout for point ($nx, $ny)")
        }

        return result
    }

    /**
     * Process all queued depth queries synchronously on the GL thread using the current live frame.
     */
    fun processPendingDepthRequests(frame: Frame) {
        val buffer = synchronized(this) { depthBuffer }
        
        while (true) {
            val request = pendingDepthRequests.poll() ?: break
            
            if (buffer == null) {
                request.callback(-1f)
                continue
            }

            try {
                val viewCoords = floatArrayOf(request.nx, request.ny)
                val cpuCoords = FloatArray(2)
                frame.transformCoordinates2d(
                    Coordinates2d.VIEW_NORMALIZED,
                    viewCoords,
                    Coordinates2d.IMAGE_NORMALIZED,
                    cpuCoords
                )
                
                val u = cpuCoords[0]
                val v = cpuCoords[1]
                
                val cx = (u * depthWidth).toInt().coerceIn(0, depthWidth - 1)
                val cy = (v * depthHeight).toInt().coerceIn(0, depthHeight - 1)
                
                // Sample 7x7 window to filter noise and invalid pixels
                val depthValues = mutableListOf<Float>()
                val radius = 3
                
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val x = (cx + dx).coerceIn(0, depthWidth - 1)
                        val y = (cy + dy).coerceIn(0, depthHeight - 1)
                        
                        val byteOffset = y * depthRowStride + x * depthPixelStride
                        val shortOffset = byteOffset / 2
                        if (shortOffset in 0 until buffer.limit()) {
                            // Apply 0x1FFF mask to extract clean 13-bit depth in millimeters (ignoring top 3 confidence bits)
                            val depthMillimeters = buffer.get(shortOffset).toInt() and 0x1FFF
                            if (depthMillimeters > 0) {
                                val depthMeters = depthMillimeters / 1000.0f
                                // Filter out unrealistic outliers
                                if (depthMeters in 0.1f..8.0f) {
                                    depthValues.add(depthMeters)
                                }
                            }
                        }
                    }
                }
                
                if (depthValues.isNotEmpty()) {
                    depthValues.sort()
                    val medianDepth = depthValues[depthValues.size / 2]
                    request.callback(medianDepth.coerceIn(0.1f, 5.0f))
                } else {
                    request.callback(-1f)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query ARCore depth on GL thread: ${e.message}")
                request.callback(-1f)
            }
        }
    }

    /**
     * Get the depth at the closest point of a normalized bounding box (using a cross sampling pattern).
     */
    fun getDepthAtBox(box: RectF): Float {
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f
        val w = box.width()
        val h = box.height()
        
        // Sample center, and 4 points on a cross pattern (25% inset from boundaries)
        val points = listOf(
            cx to cy,
            (cx - w / 4f).coerceIn(0.01f, 0.99f) to cy,
            (cx + w / 4f).coerceIn(0.01f, 0.99f) to cy,
            cx to (cy - h / 4f).coerceIn(0.01f, 0.99f),
            cx to (cy + h / 4f).coerceIn(0.01f, 0.99f)
        )
        
        val depths = mutableListOf<Float>()
        for ((px, py) in points) {
            val d = getDepthAtNormalizedPoint(px, py)
            if (d > 0f) {
                depths.add(d)
            }
        }
        
        return if (depths.isNotEmpty()) {
            depths.minOrNull() ?: -1f
        } else {
            -1f
        }
    }

    /**
     * No-op / fallback returns -1f (disabled mock heuristics).
     */
    fun estimateDistanceHeuristic(box: RectF, label: String): Float {
        return -1f
    }

    fun isDepthAvailable(): Boolean = synchronized(this) {
        depthBuffer != null
    }

    fun onResume() {
        if (isRealArCoreActive) {
            try {
                session?.resume()
                Log.i(TAG, "ARCore Session resumed.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume ARCore Session: ${e.message}")
            }
        }
    }

    fun onPause() {
        if (isRealArCoreActive) {
            try {
                session?.pause()
                Log.i(TAG, "ARCore Session paused.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pause ARCore Session: ${e.message}")
            }
        }
    }

    fun getSession(): Session? = session

    fun release() {
        isInitialized = false
        synchronized(this) {
            try {
                session?.close()
            } catch (ignored: Exception) {}
            session = null
            depthBuffer = null
            isRealArCoreActive = false
        }
        Log.i(TAG, "DepthEstimator resources released.")
    }
}
