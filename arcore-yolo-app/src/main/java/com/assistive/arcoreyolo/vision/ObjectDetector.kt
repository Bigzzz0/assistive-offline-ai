package com.assistive.arcoreyolo.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
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
 * Model: yolo11n.tflite (must be placed in assets/)
 * Input: 640x640 RGB float32 image
 * Output: bounding boxes + class confidence scores [1, 84, 8400]
 */
class ObjectDetector(private val context: Context) {

    private val TAG = "ObjectDetector"
    private val MODEL_FILENAME = "yolo11n.tflite"
    private var modelInputSize = 640
    
    var confidenceThreshold = 0.40f
    private val NMS_THRESHOLD = 0.45f

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var outputBuffer: ByteBuffer? = null
    private var isInitialized = false
    var lastInitError: String? = null
        private set
    var lastInferenceError: String? = null
        private set
    var modelInfo: String = ""
        private set

    private var inputDataType = org.tensorflow.lite.DataType.FLOAT32
    private var outputDataType = org.tensorflow.lite.DataType.FLOAT32
    private var inputScale = 1.0f
    private var inputZeroPoint = 0
    private var outputScale = 1.0f
    private var outputZeroPoint = 0
    private var outputDim2 = 8400

    // Pre-allocated static buffers for zero-allocation real-time inference
    private var scaledBitmap: Bitmap? = null
    private var canvas: android.graphics.Canvas? = null
    private var inputBuffer: ByteBuffer? = null
    private lateinit var pixels: IntArray
    private lateinit var floatArray: FloatArray

    // COCO 80-class label -> Thai name mapping
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
            } catch (e: Throwable) {
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
                } catch (e: Throwable) {
                    nnApiDelegate?.close(); nnApiDelegate = null
                    Log.w(TAG, "ObjectDetector: NNAPI failed (${e.message}), falling back to CPU")
                    options.delegates.clear()
                }
            }

            interpreter = Interpreter(modelBuffer, options)
            
            val inputTensor = interpreter?.getInputTensor(0)
            val outputTensor = interpreter?.getOutputTensor(0)
            val inputShape = inputTensor?.shape()
            val outputShape = outputTensor?.shape()
            
            Log.i(TAG, "ObjectDetector: YOLO11 loaded.")
            Log.i(TAG, "Input: shape=${inputShape?.contentToString()} type=${inputTensor?.dataType()}")
            Log.i(TAG, "Output: shape=${outputShape?.contentToString()} type=${outputTensor?.dataType()}")
            
            if (inputTensor != null && inputShape != null && inputShape.size >= 3) {
                modelInputSize = if (inputShape[1] > 3) inputShape[1] else inputShape[2]
                inputDataType = inputTensor.dataType()
                val quant = inputTensor.quantizationParams()
                inputScale = if (quant.scale != 0.0f) quant.scale else 1.0f
                inputZeroPoint = quant.zeroPoint
                inputBuffer = ByteBuffer.allocateDirect(inputTensor.numBytes()).apply {
                    order(ByteOrder.nativeOrder())
                }
            }
            
            if (outputTensor != null && outputShape != null && outputShape.size >= 2) {
                outputDataType = outputTensor.dataType()
                val quant = outputTensor.quantizationParams()
                outputScale = if (quant.scale != 0.0f) quant.scale else 1.0f
                outputZeroPoint = quant.zeroPoint
                outputDim2 = if (outputShape.size == 3) outputShape[2] else outputShape[1]
                outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes()).apply {
                    order(ByteOrder.nativeOrder())
                }
            }
            
            // Pre-allocate image processing buffers once
            scaledBitmap = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888)
            canvas = android.graphics.Canvas(scaledBitmap!!)
            pixels = IntArray(modelInputSize * modelInputSize)
            floatArray = FloatArray(modelInputSize * modelInputSize * 3)
            
            modelInfo = "In:${inputDataType} Out:${outputDataType} S:${(outputScale * 10000).toInt() / 10000f} ZP:${outputZeroPoint} Size:${modelInputSize}px"
            isInitialized = true
            Log.i(TAG, "ObjectDetector initialized successfully")
            true
        } catch (e: Throwable) {
            lastInitError = "${e.javaClass.simpleName}: ${e.message ?: "Unknown Error"}"
            Log.e(TAG, "ObjectDetector initialization failed: ${e.message}", e)
            false
        }
    }

    /**
     * Run inference on a camera frame bitmap.
     * @return List of detected objects above confidence threshold after NMS
     */
    fun detect(bitmap: Bitmap, lock: Any): List<DetectedObject> {
        val interp = interpreter ?: return emptyList()

        return try {
            val targetBitmap = scaledBitmap ?: Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888).also { scaledBitmap = it }
            val targetCanvas = canvas ?: android.graphics.Canvas(targetBitmap).also { canvas = it }
            
            // Scale and draw input bitmap in-place inside the synchronized block
            synchronized(lock) {
                targetCanvas.drawBitmap(bitmap, null, RectF(0f, 0f, modelInputSize.toFloat(), modelInputSize.toFloat()), null)
            }
            
            val buffer = bitmapToByteBuffer(targetBitmap)

            val shape = interp.getOutputTensor(0).shape() // [1, 84, 8400]
            val outBuffer = this.outputBuffer ?: ByteBuffer.allocateDirect(interp.getOutputTensor(0).numBytes()).apply {
                order(ByteOrder.nativeOrder())
            }.also { this.outputBuffer = it }
            
            outBuffer.rewind()
            interp.run(buffer, outBuffer)
            outBuffer.rewind()

            val results = mutableListOf<DetectedObject>()
            val dim1 = if (shape.size == 3) shape[1] else shape[0]
            val dim2 = if (shape.size == 3) shape[2] else shape[1]
            val isRowFormat = dim1 < dim2
            val numBoxes = if (isRowFormat) dim2 else dim1
            val channels = if (isRowFormat) dim1 else dim2
            val numClasses = channels - 4

            var maxFrameScore = 0f
            var maxFrameClass = -1

            val getVal = { r: Int, col: Int ->
                val index = r * dim2 + col
                if (outputDataType == org.tensorflow.lite.DataType.FLOAT32) {
                    outBuffer.getFloat(index * 4)
                } else if (outputDataType == org.tensorflow.lite.DataType.UINT8) {
                    val qVal = outBuffer.get(index).toInt() and 0xFF
                    (qVal - outputZeroPoint) * outputScale
                } else {
                    // INT8
                    val qVal = outBuffer.get(index).toInt()
                    (qVal - outputZeroPoint) * outputScale
                }
            }

            for (c in 0 until numBoxes) {
                val cx: Float
                val cy: Float
                val w: Float
                val h: Float

                if (isRowFormat) {
                    cx = getVal(0, c)
                    cy = getVal(1, c)
                    w = getVal(2, c)
                    h = getVal(3, c)
                } else {
                    cx = getVal(c, 0)
                    cy = getVal(c, 1)
                    w = getVal(c, 2)
                    h = getVal(c, 3)
                }

                // Find class with maximum score
                var maxScore = 0f
                var maxClassIdx = -1
                for (classIdx in 0 until numClasses) {
                    val score = if (isRowFormat) {
                        getVal(4 + classIdx, c)
                    } else {
                        getVal(c, 4 + classIdx)
                    }
                    if (score > maxScore) {
                        maxScore = score
                        maxClassIdx = classIdx
                    }
                }

                if (maxScore > maxFrameScore) {
                    maxFrameScore = maxScore
                    maxFrameClass = maxClassIdx
                }

                if (maxScore >= confidenceThreshold) {
                    val label = getLabel(maxClassIdx)
                    
                    // Convert bounding box center coords to normalized [0..1] rectangle
                    val left = (cx - w / 2f) / modelInputSize
                    val top = (cy - h / 2f) / modelInputSize
                    val right = (cx + w / 2f) / modelInputSize
                    val bottom = (cy + h / 2f) / modelInputSize

                    val rect = RectF(
                        left.coerceIn(0f, 1f),
                        top.coerceIn(0f, 1f),
                        right.coerceIn(0f, 1f),
                        bottom.coerceIn(0f, 1f)
                    )
                    results.add(DetectedObject(label, thaiLabels[label] ?: label, maxScore, rect))
                }
            }

            val maxScorePercent = (maxFrameScore * 100).toInt()
            val classLabel = if (maxFrameClass >= 0) getLabel(maxFrameClass) else "none"
            modelInfo = "In:${inputDataType} Out:${outputDataType} S:${(outputScale * 10000).toInt() / 10000f} ZP:${outputZeroPoint} Size:${modelInputSize}px | maxVal=${maxScorePercent}% ($classLabel)"

            val filteredResults = applyNMS(results)
            Log.d(TAG, "Detected ${filteredResults.size} objects after NMS (originally ${results.size})")
            lastInferenceError = null
            filteredResults
        } catch (e: Exception) {
            lastInferenceError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "detect() failed: ${e.message}", e)
            emptyList()
        }
    }

    fun release() {
        try { interpreter?.close() } catch (ignored: Exception) {}
        try { gpuDelegate?.close() } catch (ignored: Exception) {}
        try { nnApiDelegate?.close() } catch (ignored: Exception) {}
        scaledBitmap?.recycle()
        scaledBitmap = null
        canvas = null
        interpreter = null; gpuDelegate = null; nnApiDelegate = null; outputBuffer = null
        isInitialized = false
        Log.i(TAG, "ObjectDetector released")
    }

    private fun loadModelFromAssets(): MappedByteBuffer {
        val fd = context.assets.openFd(MODEL_FILENAME)
        return FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = inputBuffer ?: ByteBuffer.allocateDirect(1 * modelInputSize * modelInputSize * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
            inputBuffer = this
        }
        
        bitmap.getPixels(pixels, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize)
        
        buffer.rewind()
        if (inputDataType == org.tensorflow.lite.DataType.FLOAT32) {
            var outIdx = 0
            for (i in pixels.indices) {
                val px = pixels[i]
                floatArray[outIdx++] = ((px shr 16) and 0xFF) / 255.0f
                floatArray[outIdx++] = ((px shr 8) and 0xFF) / 255.0f
                floatArray[outIdx++] = (px and 0xFF) / 255.0f
            }
            buffer.asFloatBuffer().put(floatArray)
        } else {
            // Quantized model (INT8 or UINT8)
            for (i in pixels.indices) {
                val px = pixels[i]
                val r = ((px shr 16) and 0xFF) / 255.0f
                val g = ((px shr 8) and 0xFF) / 255.0f
                val b = (px and 0xFF) / 255.0f
                
                val qr = ((r / inputScale) + inputZeroPoint).toInt().coerceIn(-128, 255).toByte()
                val qg = ((g / inputScale) + inputZeroPoint).toInt().coerceIn(-128, 255).toByte()
                val qb = ((b / inputScale) + inputZeroPoint).toInt().coerceIn(-128, 255).toByte()
                
                buffer.put(qr)
                buffer.put(qg)
                buffer.put(qb)
            }
        }
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
