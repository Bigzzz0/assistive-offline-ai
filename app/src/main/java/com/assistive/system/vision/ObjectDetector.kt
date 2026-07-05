package com.assistive.system.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.assistive.system.logging.AppLogger as Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Represents a single object detected in a camera frame.
 */
data class DetectedObject(
    val label: String,
    val labelThai: String,
    val confidence: Float,
    val boundingBox: RectF  // normalized [0..1] coordinates
)

/**
 * ObjectDetector wraps EfficientDet-Lite0 TFLite model for real-time object detection.
 *
 * Model: efficientdet_lite0.tflite (must be placed in app/src/main/assets/)
 * Input: 320x320 RGB uint8 image
 * Output: bounding boxes, class labels, confidence scores, count
 *
 * Backend priority: GPU Delegate → NNAPI Delegate → CPU
 * Falls back gracefully so the app always works on any device.
 */
class ObjectDetector(private val context: Context) {

    private val TAG = "ObjectDetector"
    private val MODEL_FILENAME = "efficientdet_lite0.tflite"
    private val INPUT_SIZE = 320
    private val MAX_DETECTIONS = 25
    private val CONFIDENCE_THRESHOLD = 0.45f

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var isInitialized = false

    // COCO 80-class label → Thai name mapping (for TTS)
    private val thaiLabels = mapOf(
        "person"        to "คน",
        "bicycle"       to "จักรยาน",
        "car"           to "รถยนต์",
        "motorcycle"    to "มอเตอร์ไซค์",
        "bus"           to "รถเมล์",
        "truck"         to "รถบรรทุก",
        "chair"         to "เก้าอี้",
        "couch"         to "โซฟา",
        "table"         to "โต๊ะ",
        "bed"           to "เตียง",
        "toilet"        to "ห้องน้ำ",
        "door"          to "ประตู",
        "stairs"        to "บันได",
        "bottle"        to "ขวด",
        "cup"           to "แก้วน้ำ",
        "book"          to "หนังสือ",
        "laptop"        to "แล็ปท็อป",
        "cell phone"    to "โทรศัพท์",
        "backpack"      to "กระเป๋า",
        "umbrella"      to "ร่ม",
        "handbag"       to "กระเป๋าถือ",
        "dog"           to "สุนัข",
        "cat"           to "แมว",
        "potted plant"  to "ต้นไม้กระถาง",
        "tv"            to "โทรทัศน์",
        "keyboard"      to "แป้นพิมพ์",
        "mouse"         to "เมาส์"
    )

    /**
     * Initialize TFLite interpreter. Call once on a background thread.
     */
    fun initialize(): Boolean {
        if (isInitialized) return true
        return try {
            val modelBuffer = loadModelFromAssets()
            val options = Interpreter.Options().apply { numThreads = 2 }

            // 1. Try GPU Delegate first
            var useGpu = false
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate!!)
                useGpu = true
                Log.i(TAG, "ObjectDetector: GPU Delegate activated")
            } catch (e: Exception) {
                gpuDelegate?.close(); gpuDelegate = null
                Log.w(TAG, "ObjectDetector: GPU Delegate failed (${e.message}), trying NNAPI")
                options.delegates.clear()
            }

            // 2. Try NNAPI if GPU failed
            if (!useGpu) {
                try {
                    nnApiDelegate = NnApiDelegate()
                    options.addDelegate(nnApiDelegate!!)
                    Log.i(TAG, "ObjectDetector: NNAPI Delegate activated")
                } catch (e: Exception) {
                    nnApiDelegate?.close(); nnApiDelegate = null
                    Log.w(TAG, "ObjectDetector: NNAPI failed (${e.message}), falling back to CPU")
                    options.delegates.clear()
                }
            }

            interpreter = Interpreter(modelBuffer, options)
            isInitialized = true
            Log.i(TAG, "ObjectDetector initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ObjectDetector initialization failed: ${e.message}", e)
            false
        }
    }

    /**
     * Run inference on a camera frame bitmap.
     * @return List of detected objects above confidence threshold
     */
    fun detect(bitmap: Bitmap): List<DetectedObject> {
        val interp = interpreter ?: return emptyList()

        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val inputBuffer = bitmapToByteBuffer(scaledBitmap)
            if (scaledBitmap != bitmap) scaledBitmap.recycle()

            // EfficientDet-Lite0 output tensors (metadata-embedded model):
            // [0] boxes:   [1, 25, 4] — normalized (ymin, xmin, ymax, xmax)
            // [1] classes: [1, 25]    — class index (float)
            // [2] scores:  [1, 25]    — confidence score
            // [3] count:   [1]        — number of valid detections
            val outputBoxes   = Array(1) { Array(MAX_DETECTIONS) { FloatArray(4) } }
            val outputClasses = Array(1) { FloatArray(MAX_DETECTIONS) }
            val outputScores  = Array(1) { FloatArray(MAX_DETECTIONS) }
            val outputCount   = FloatArray(1)

            val outputs = mapOf(0 to outputBoxes, 1 to outputClasses, 2 to outputScores, 3 to outputCount)
            interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

            val count = outputCount[0].toInt().coerceIn(0, MAX_DETECTIONS)
            val results = mutableListOf<DetectedObject>()

            for (i in 0 until count) {
                val score = outputScores[0][i]
                if (score < CONFIDENCE_THRESHOLD) continue
                val label = getLabel(outputClasses[0][i].toInt())
                val box = outputBoxes[0][i]
                // box = [ymin, xmin, ymax, xmax] normalized
                results.add(DetectedObject(label, thaiLabels[label] ?: label, score,
                    RectF(box[1], box[0], box[3], box[2])))
            }

            Log.d(TAG, "Detected ${results.size} objects above threshold")
            results
        } catch (e: Exception) {
            Log.e(TAG, "detect() failed: ${e.message}", e)
            emptyList()
        }
    }

    fun release() {
        try { interpreter?.close() } catch (ignored: Exception) {}
        try { gpuDelegate?.close() } catch (ignored: Exception) {}
        try { nnApiDelegate?.close() } catch (ignored: Exception) {}
        interpreter = null; gpuDelegate = null; nnApiDelegate = null
        isInitialized = false
        Log.i(TAG, "ObjectDetector released")
    }

    // ─── Private helpers ────────────────────────────────────────────────────

    private fun loadModelFromAssets(): MappedByteBuffer {
        val fd = context.assets.openFd(MODEL_FILENAME)
        return FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            buffer.put(((px shr 16) and 0xFF).toByte())
            buffer.put(((px shr 8) and 0xFF).toByte())
            buffer.put((px and 0xFF).toByte())
        }
        buffer.rewind()
        return buffer
    }

    private fun getLabel(index: Int): String {
        val labels = listOf(
            "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
            "traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat",
            "dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack",
            "umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball",
            "kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket",
            "bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple",
            "sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair",
            "couch","potted plant","bed","dining table","toilet","tv","laptop","mouse",
            "remote","keyboard","cell phone","microwave","oven","toaster","sink","refrigerator",
            "book","clock","vase","scissors","teddy bear","hair drier","toothbrush"
        )
        return if (index in labels.indices) labels[index] else "object"
    }
}
