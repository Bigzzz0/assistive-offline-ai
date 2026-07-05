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
 * ObjectDetector wraps YOLOv11 Nano TFLite model for real-time object detection.
 *
 * Model: yolo11n.tflite (must be placed in app/src/main/assets/)
 * Input: 640x640 RGB float32 image
 * Output: bounding boxes + class confidence scores [1, 84, 8400]
 *
 * Backend priority: GPU Delegate → NNAPI Delegate → CPU
 */
class ObjectDetector(private val context: Context) {

    private val TAG = "ObjectDetector"
    private val MODEL_FILENAME = "yolo11n.tflite"
    private val INPUT_SIZE = 640
    private val CONFIDENCE_THRESHOLD = 0.40f
    private val NMS_THRESHOLD = 0.45f

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var isInitialized = false
    private var outputArray: Array<Array<FloatArray>>? = null

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
            val options = Interpreter.Options().apply { numThreads = 4 }

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
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            Log.i(TAG, "ObjectDetector: YOLO11 loaded, output shape is ${outputShape?.contentToString()}")
            if (outputShape != null) {
                outputArray = Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
            }
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
     * @return List of detected objects above confidence threshold after NMS
     */
    fun detect(bitmap: Bitmap): List<DetectedObject> {
        val interp = interpreter ?: return emptyList()

        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val inputBuffer = bitmapToByteBuffer(scaledBitmap)
            if (scaledBitmap != bitmap) scaledBitmap.recycle()

            val shape = interp.getOutputTensor(0).shape() // [1, 84, 8400] or [1, 8400, 84]
            val outputArray = this.outputArray ?: Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
            
            interp.run(inputBuffer, outputArray)

            val results = mutableListOf<DetectedObject>()
            val isRowFormat = shape[1] < shape[2] // true if [1, 84, 8400]
            val numBoxes = if (isRowFormat) shape[2] else shape[1]
            val numClasses = 80 // COCO dataset

            for (c in 0 until numBoxes) {
                // Read raw center-x, center-y, width, height (in 640x640 scale)
                val cx: Float
                val cy: Float
                val w: Float
                val h: Float

                if (isRowFormat) {
                    cx = outputArray[0][0][c]
                    cy = outputArray[0][1][c]
                    w = outputArray[0][2][c]
                    h = outputArray[0][3][c]
                } else {
                    cx = outputArray[0][c][0]
                    cy = outputArray[0][c][1]
                    w = outputArray[0][c][2]
                    h = outputArray[0][c][3]
                }

                // Find class with maximum score
                var maxScore = 0f
                var maxClassIdx = -1
                for (classIdx in 0 until numClasses) {
                    val score = if (isRowFormat) {
                        outputArray[0][4 + classIdx][c]
                    } else {
                        outputArray[0][c][4 + classIdx]
                    }
                    if (score > maxScore) {
                        maxScore = score
                        maxClassIdx = classIdx
                    }
                }

                if (maxScore >= CONFIDENCE_THRESHOLD) {
                    val label = getLabel(maxClassIdx)
                    
                    // Convert bounding box center coords to normalized [0..1] rectangle
                    val left = (cx - w / 2f) / INPUT_SIZE
                    val top = (cy - h / 2f) / INPUT_SIZE
                    val right = (cx + w / 2f) / INPUT_SIZE
                    val bottom = (cy + h / 2f) / INPUT_SIZE

                    val rect = RectF(
                        left.coerceIn(0f, 1f),
                        top.coerceIn(0f, 1f),
                        right.coerceIn(0f, 1f),
                        bottom.coerceIn(0f, 1f)
                    )
                    results.add(DetectedObject(label, thaiLabels[label] ?: label, maxScore, rect))
                }
            }

            // Apply Non-Maximum Suppression to remove duplicate boxes
            val filteredResults = applyNMS(results)
            Log.d(TAG, "Detected ${filteredResults.size} objects after NMS (originally ${results.size})")
            filteredResults
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
        // float32 takes 4 bytes per float (1 * 640 * 640 * 3 * 4 = 4,915,200 bytes)
        val buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        
        val floatArray = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
        var outIdx = 0
        for (i in pixels.indices) {
            val px = pixels[i]
            floatArray[outIdx++] = ((px shr 16) and 0xFF) / 255.0f
            floatArray[outIdx++] = ((px shr 8) and 0xFF) / 255.0f
            floatArray[outIdx++] = (px and 0xFF) / 255.0f
        }
        buffer.asFloatBuffer().put(floatArray)
        buffer.rewind()
        return buffer
    }

    private fun applyNMS(objects: List<DetectedObject>): List<DetectedObject> {
        val sorted = objects.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<DetectedObject>()
        
        while (sorted.isNotEmpty()) {
            val current = sorted.removeAt(0)
            selected.add(current)
            
            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calculateIoU(current.boundingBox, next.boundingBox) > NMS_THRESHOLD) {
                    iterator.remove()
                }
            }
        }
        return selected
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = maxOf(box1.left, box2.left)
        val intersectionTop = maxOf(box1.top, box2.top)
        val intersectionRight = minOf(box1.right, box2.right)
        val intersectionBottom = minOf(box1.bottom, box2.bottom)

        if (intersectionLeft >= intersectionRight || intersectionTop >= intersectionBottom) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0f) intersectionArea / unionArea else 0f
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
