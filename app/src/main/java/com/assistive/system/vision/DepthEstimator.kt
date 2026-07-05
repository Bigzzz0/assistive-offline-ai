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

    @Volatile
    private var lastArCoreFrame: Frame? = null

    private var depthWidth = 0
    private var depthHeight = 0
    private var depthRowStride = 0
    private var depthPixelStride = 0
    
    @Volatile
    private var depthBuffer: java.nio.ShortBuffer? = null

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
     * No-op since frame updates are submitted directly via updateArCoreFrame.
     */
    fun updateFrame() {
    }

    /**
     * Copy the current depth image contents to a thread-safe short buffer.
     * Invoked from the OpenGL rendering thread to prevent native leaks.
     */
    fun updateArCoreFrame(frame: Frame, depthImage: android.media.Image?) {
        synchronized(this) {
            lastArCoreFrame = frame
            if (depthImage != null) {
                try {
                    depthWidth = depthImage.width
                    depthHeight = depthImage.height
                    
                    val plane = depthImage.planes[0]
                    depthRowStride = plane.rowStride
                    depthPixelStride = plane.pixelStride
                    
                    val sourceBuffer = plane.buffer.order(java.nio.ByteOrder.nativeOrder())
                    val capacity = sourceBuffer.remaining()
                    val targetBytes = ByteArray(capacity)
                    sourceBuffer.get(targetBytes)
                    
                    depthBuffer = java.nio.ByteBuffer.wrap(targetBytes)
                        .order(java.nio.ByteOrder.nativeOrder())
                        .asShortBuffer()
                } catch (e: Exception) {
                    Log.w(TAG, "Error copying depth image: ${e.message}")
                    depthBuffer = null
                } finally {
                    depthImage.close()
                }
            } else {
                depthBuffer = null
            }
        }
    }

    /**
     * Get depth (in meters) at a normalized screen coordinate.
     * Uses real ARCore Depth API if active, otherwise returns -1f (no mock fallback).
     */
    fun getDepthAtNormalizedPoint(nx: Float, ny: Float): Float {
        val frame = lastArCoreFrame
        val buffer = synchronized(this) { depthBuffer }
        
        if (frame != null && buffer != null) {
            try {
                val viewCoords = floatArrayOf(nx, ny)
                val cpuCoords = FloatArray(2)
                frame.transformCoordinates2d(
                    Coordinates2d.VIEW_NORMALIZED,
                    viewCoords,
                    Coordinates2d.IMAGE_NORMALIZED,
                    cpuCoords
                )
                
                val u = cpuCoords[0]
                val v = cpuCoords[1]
                
                val x = (u * depthWidth).toInt().coerceIn(0, depthWidth - 1)
                val y = (v * depthHeight).toInt().coerceIn(0, depthHeight - 1)
                
                val byteOffset = y * depthRowStride + x * depthPixelStride
                val shortOffset = byteOffset / 2
                if (shortOffset in 0 until buffer.limit()) {
                    val depthMillimeters = buffer.get(shortOffset).toInt() and 0xFFFF
                    if (depthMillimeters > 0) {
                        val depthMeters = depthMillimeters / 1000.0f
                        Log.d(TAG, "Real ARCore Depth: ${String.format("%.2f", depthMeters)}m at ($x, $y) mapped from ($nx, $ny)")
                        return depthMeters.coerceIn(0.1f, 5.0f)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query ARCore depth: ${e.message}")
            }
        }

        return -1f
    }

    /**
     * Get the depth at the center of a normalized bounding box.
     */
    fun getDepthAtBox(box: RectF): Float {
        val cy = (box.top + box.bottom) / 2f
        return getDepthAtNormalizedPoint(0.5f, cy)
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
