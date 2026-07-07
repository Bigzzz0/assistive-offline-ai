package com.assistive.arcoreyolo.vision

import android.app.Activity
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Coordinates2d

/**
 * DepthEstimator provides real-time distance measurements (in meters) using the Google ARCore Depth API.
 */
class DepthEstimator {

    private val TAG = "DepthEstimator"
    private var isInitialized = false

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

    // Thread-safe batch depth request queue for GL thread processing
    data class BatchDepthRequest(
        val points: List<Pair<Float, Float>>,
        val callback: (List<Float>) -> Unit
    )

    private val pendingBatchRequests = java.util.concurrent.ConcurrentLinkedQueue<BatchDepthRequest>()

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
     * Initialize native ARCore Session with Depth API enabled.
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
     * Copy the current depth image contents to a thread-safe short buffer.
     * Invoked from the OpenGL rendering thread.
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
     * Submits a list of coordinates to be mapped and sampled in batch on the GL thread.
     * Blocks the calling thread safely for up to 50ms.
     */
    fun getDepthAtPoints(points: List<Pair<Float, Float>>): List<Float> {
        if (!isRealArCoreActive || points.isEmpty()) return List(points.size) { -1f }

        val latch = java.util.concurrent.CountDownLatch(1)
        var result = emptyList<Float>()

        pendingBatchRequests.add(BatchDepthRequest(points) { depths ->
            result = depths
            latch.countDown()
        })

        try {
            latch.await(50L, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Batch depth query latch timeout for ${points.size} points")
        }

        return if (result.isNotEmpty()) result else List(points.size) { -1f }
    }

    /**
     * Process all queued batch requests synchronously on the GL thread using the current live frame.
     */
    fun processPendingDepthRequests(frame: Frame) {
        val buffer = synchronized(this) { depthBuffer }
        
        while (true) {
            val batch = pendingBatchRequests.poll() ?: break
            
            if (buffer == null) {
                batch.callback(List(batch.points.size) { -1f })
                continue
            }

            val results = ArrayList<Float>(batch.points.size)
            for (i in batch.points.indices) {
                val point = batch.points[i]
                try {
                    val viewCoords = floatArrayOf(point.first, point.second)
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
                        results.add(depthValues[depthValues.size / 2])
                    } else {
                        results.add(-1f)
                    }
                } catch (e: Exception) {
                    results.add(-1f)
                }
            }
            batch.callback(results)
        }
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
