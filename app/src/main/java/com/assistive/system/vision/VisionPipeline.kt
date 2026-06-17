package com.assistive.system.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

class VisionPipeline(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val isFrameRequested: () -> Boolean,
    private val onSceneChanged: (ByteArray) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // Motion detection state
    private var isMoving = false
    private var lastMotionTime = 0L
    private val MOVEMENT_THRESHOLD = 1.0f // m/s^2 for linear acceleration
    private val ROTATION_THRESHOLD = 0.5f // rad/s for gyroscope

    // Scene change detection state (downsampled Y-plane)
    private var lastDownsampledFrame: IntArray? = null
    private val DOWNSAMPLE_WIDTH = 32
    private val DOWNSAMPLE_HEIGHT = 32
    private val SCENE_DIFF_THRESHOLD = 0.08f // 8% difference threshold
    private var lastProcessedTime = 0L
    private val FRAME_INTERVAL_MS = 200L // 5 FPS (200ms)

    init {
        registerSensors()
    }

    private fun registerSensors() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = sqrt(x * x + y * y + z * z)
            if (magnitude > MOVEMENT_THRESHOLD) {
                isMoving = true
                lastMotionTime = now
            } else if (now - lastMotionTime > 1500) {
                isMoving = false
            }
        } else if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val wx = event.values[0]
            val wy = event.values[1]
            val wz = event.values[2]
            val rotMagnitude = sqrt(wx * wx + wy * wy + wz * wz)
            if (rotMagnitude > ROTATION_THRESHOLD) {
                isMoving = true
                lastMotionTime = now
            } else if (now - lastMotionTime > 1500) {
                isMoving = false
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun getAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            val now = System.currentTimeMillis()
            val frameRequested = isFrameRequested()
            if (!frameRequested && (now - lastProcessedTime < FRAME_INTERVAL_MS)) {
                imageProxy.close()
                return@Analyzer
            }
            lastProcessedTime = now
            executor.execute {
                processImage(imageProxy)
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        // Zero-CPU idle frame gating: close imageProxy and return immediately if no active request
        if (!isFrameRequested()) {
            imageProxy.close()
            return
        }

        val image = imageProxy.image
        if (image == null || image.format != ImageFormat.YUV_420_888) {
            imageProxy.close()
            return
        }

        val yBuffer = image.planes[0].buffer
        val yRowStride = image.planes[0].rowStride
        val yPixelStride = image.planes[0].pixelStride
        val width = imageProxy.width
        val height = imageProxy.height

        // 1. Perform extremely fast downsampling of Y-plane (grayscale) to 32x32
        val currentDownsampled = downsampleYPlane(yBuffer, width, height, yRowStride, yPixelStride)

        // 2. Compute scene change difference
        val diff = calculateFrameDifference(currentDownsampled, lastDownsampledFrame)
        lastDownsampledFrame = currentDownsampled

        // 3. Frame logic decision:
        // Trigger inference ONLY when:
        // - The user is NOT actively swinging/moving the device (avoids motion blur and saves CPU/GPU)
        // - The scene difference exceeds our threshold (visual change occurred)
        val hasSceneChanged = diff > SCENE_DIFF_THRESHOLD
        
        val frameRequested = isFrameRequested()
        Log.d("VisionPipeline", "Frame processed: motion=$isMoving, diff=${String.format("%.3f", diff)}, changed=$hasSceneChanged, requested=$frameRequested")

        if (frameRequested || (!isMoving && hasSceneChanged)) {
            // Convert current image proxy directly to a JPEG ByteArray for VLM model consumption
            val jpegBytes = imageProxyToJpegBytes(imageProxy)
            onSceneChanged(jpegBytes)
        }

        imageProxy.close()
    }

    private fun downsampleYPlane(
        yBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): IntArray {
        val result = IntArray(DOWNSAMPLE_WIDTH * DOWNSAMPLE_HEIGHT)
        val xStep = width / DOWNSAMPLE_WIDTH
        val yStep = height / DOWNSAMPLE_HEIGHT
        val capacity = yBuffer.capacity()

        for (y in 0 until DOWNSAMPLE_HEIGHT) {
            val sourceY = y * yStep
            val rowOffset = sourceY * rowStride
            val rowTargetIndex = y * DOWNSAMPLE_WIDTH
            for (x in 0 until DOWNSAMPLE_WIDTH) {
                val sourceX = x * xStep
                val index = rowOffset + sourceX * pixelStride
                if (index < capacity) {
                    val pixelVal = yBuffer.get(index).toInt() and 0xFF
                    result[rowTargetIndex + x] = pixelVal
                }
            }
        }
        return result
    }

    private fun calculateFrameDifference(current: IntArray, last: IntArray?): Float {
        if (last == null || current.size != last.size) return 1.0f

        var totalDiff = 0L
        for (i in current.indices) {
            totalDiff += abs(current[i] - last[i])
        }
        
        val maxPossibleDiff = current.size * 255.0f
        return totalDiff / maxPossibleDiff
    }

    private fun imageProxyToJpegBytes(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 50, out)
        return out.toByteArray()
    }

    fun shutdown() {
        unregisterSensors()
        executor.shutdown()
    }
}
