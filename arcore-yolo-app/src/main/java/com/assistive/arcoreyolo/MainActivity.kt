package com.assistive.arcoreyolo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.assistive.arcoreyolo.audio.TtsManager
import com.assistive.arcoreyolo.haptic.HapticManager
import com.assistive.arcoreyolo.vision.*
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Data class representing a processed object detection result with calculated screen positions and distance.
 */
data class DisplayedObject(
    val label: String,
    val labelThai: String,
    val confidence: Float,
    val screenRect: RectF, // mapped to viewport coordinates [0..1]
    val distanceMeters: Float
)

/**
 * Data class representing raw object detection details in raw sensor IMAGE space.
 */
data class RawDetection(
    val label: String,
    val labelThai: String,
    val confidence: Float,
    val boundingBox: RectF, // IMAGE_NORMALIZED coordinates
    val distanceMeters: Float
)

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    // Core pipelines
    private var objectDetector: ObjectDetector? = null
    private var depthEstimator: DepthEstimator? = null
    private var ttsManager: TtsManager? = null
    private var hapticManager: HapticManager? = null
    
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private lateinit var inferenceExecutor: ExecutorService

    // Live state bindings for Compose
    private var detectedObjectsState = mutableStateListOf<DisplayedObject>()
    private var centerDistanceState by mutableStateOf(-1f)
    private var arCoreStatusState by mutableStateOf("Initializing...")
    private var arCoreActiveState by mutableStateOf(false)
    private var yoloLatencyState by mutableStateOf(0L)
    private var inferenceFpsState by mutableStateOf(0)

    // User settings states
    private var isTtsEnabled by mutableStateOf(true)
    private var isHapticEnabled by mutableStateOf(true)
    private var confidenceThreshold by mutableStateOf(0.40f)
    private var speechIntervalSeconds by mutableStateOf(4)
    private var ttsLanguageState by mutableStateOf("th")

    // Processing locks & loops
    private val frameLock = Any()
    private var pipelineBitmap: Bitmap? = null
    private var hasNewFrame = false
    private var lastHapticTime = 0L

    // Thread-safe raw detections from background YOLO thread
    private val rawDetections = mutableListOf<RawDetection>()
    private val rawDetectionsLock = Any()


    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            initializePipelines()
        } else {
            arCoreStatusState = "Camera Permission Denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        inferenceExecutor = Executors.newSingleThreadExecutor()

        // Request permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initializePipelines()
        } else {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF10B981), // Emerald
                    secondary = Color(0xFF3B82F6), // Blue
                    background = Color.Black,
                    surface = Color(0xFF18181B) // Slate 900
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ArCoreYoloScreen()
                }
            }
        }
    }

    private fun initializePipelines() {
        ttsManager = TtsManager(applicationContext)
        hapticManager = HapticManager(applicationContext)
        depthEstimator = DepthEstimator()

        // 1. Initialize YOLO Nano in a background thread
        Thread {
            try {
                val detector = ObjectDetector(applicationContext)
                detector.confidenceThreshold = confidenceThreshold
                if (detector.initialize()) {
                    objectDetector = detector
                    Log.i(TAG, "ObjectDetector (YOLO) initialized successfully.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "YOLO initialization failed", e)
            }
        }.start()

        // 2. Initialize ARCore Depth API
        mainScope.launch {
            val depth = depthEstimator ?: return@launch
            val arReady = depth.checkAndInstallArCore(this@MainActivity)
            if (arReady) {
                depth.initialize(this@MainActivity)
                depth.onResume()
                if (depth.isRealArCoreActive) {
                    arCoreStatusState = "ARCore Depth Active"
                    arCoreActiveState = true
                } else {
                    arCoreStatusState = depth.arCoreErrorMessage ?: "ARCore Depth Unsupported"
                    arCoreActiveState = false
                }
            } else {
                arCoreStatusState = depth.arCoreErrorMessage ?: "Google Play Services for AR Required"
                arCoreActiveState = false
            }
        }

        // 3. Start Text-To-Speech periodic announcer loop
        mainScope.launch {
            while (true) {
                delay(speechIntervalSeconds * 1000L)
                if (isTtsEnabled && detectedObjectsState.isNotEmpty()) {
                    announceObjects()
                }
            }
        }
    }

    private fun announceObjects() {
        // Collect objects closer than 3.5m and sort by proximity
        val nearby = detectedObjectsState
            .filter { it.distanceMeters > 0.1f && it.distanceMeters < 3.5f }
            .sortedBy { it.distanceMeters }
            .take(3) // Speak top 3 closest items to avoid overload

        if (nearby.isEmpty()) return

        val builder = StringBuilder()
        if (ttsLanguageState == "th") {
            builder.append("พบ ")
            for (i in nearby.indices) {
                val item = nearby[i]
                val distStr = String.format(Locale.US, "%.1f", item.distanceMeters)
                builder.append("${item.labelThai} ระยะ $distStr เมตร")
                if (i < nearby.size - 1) builder.append(" และ ")
            }
        } else {
            builder.append("Detected ")
            for (i in nearby.indices) {
                val item = nearby[i]
                val distStr = String.format(Locale.US, "%.1f", item.distanceMeters)
                builder.append("${item.label} at $distStr meters")
                if (i < nearby.size - 1) builder.append(", and ")
            }
        }
        ttsManager?.speak(builder.toString())
    }

    override fun onResume() {
        super.onResume()
        try {
            depthEstimator?.onResume()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume depth estimator: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            depthEstimator?.onPause()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause depth estimator: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inferenceExecutor.shutdown()
        objectDetector?.release()
        depthEstimator?.release()
        ttsManager?.release()
        synchronized(frameLock) {
            pipelineBitmap?.recycle()
            pipelineBitmap = null
        }
    }

    // ==========================================
    // OpenGL ARCore Renderer
    // ==========================================
    inner class ArRenderer(private val session: Session) : GLSurfaceView.Renderer {
        private val backgroundRenderer = BackgroundRenderer()
        private var lastProcessedTime = 0L
        private var frameCounter = 0
        private var fpsTimer = 0L

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            backgroundRenderer.createOnGlThread(applicationContext)

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val cameraTextureId = textures[0]
            
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            backgroundRenderer.cameraTextureId = cameraTextureId
            session.setCameraTextureName(cameraTextureId)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val rotation = windowManager.defaultDisplay.rotation
            session.setDisplayGeometry(rotation, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            try {
                val frame = session.update()
                backgroundRenderer.draw(frame)

                // 1. Process and update depth buffers
                val depthImage = try {
                    frame.acquireRawDepthImage16Bits()
                } catch (e: Exception) {
                    try {
                        frame.acquireDepthImage16Bits()
                    } catch (e2: Exception) {
                        null
                    }
                }

                if (depthImage != null) {
                    try {
                        val width = depthImage.width
                        val height = depthImage.height
                        val plane = depthImage.planes[0]
                        val rowStride = plane.rowStride
                        val pixelStride = plane.pixelStride

                        val sourceBuffer = plane.buffer.order(java.nio.ByteOrder.nativeOrder())
                        val capacity = sourceBuffer.remaining()
                        val targetBytes = ByteArray(capacity)
                        sourceBuffer.get(targetBytes)

                        val depthBuffer = java.nio.ByteBuffer.wrap(targetBytes)
                            .order(java.nio.ByteOrder.nativeOrder())
                            .asShortBuffer()

                        depthEstimator?.updateDepthBuffer(frame, depthBuffer, width, height, rowStride, pixelStride)
                        depthEstimator?.processPendingDepthRequests(frame)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to copy depth buffer: ${e.message}")
                    } finally {
                        depthImage.close()
                    }
                } else {
                    depthEstimator?.updateDepthBuffer(frame, null, 0, 0, 0, 0)
                    depthEstimator?.processPendingDepthRequests(frame)
                }

                // 1.2 Sample center screen depth immediately on the GL thread (30 FPS)
                if (depthEstimator?.isRealArCoreActive == true) {
                    val dist = depthEstimator?.getDepthAtPointImmediate(0.5f to 0.5f, isNormalizedImageSpace = false, frame = frame) ?: -1f
                    mainScope.launch {
                        centerDistanceState = dist
                    }
                }

                // 1.5 Update coordinate transformations on the active frame for current raw detections
                val currentRawDetections = synchronized(rawDetectionsLock) { ArrayList(rawDetections) }
                val updatedDisplayList = mutableListOf<DisplayedObject>()
                for (raw in currentRawDetections) {
                    val box = raw.boundingBox
                    val imgCoords = floatArrayOf(
                        box.left, box.top,
                        box.right, box.bottom
                    )
                    val viewCoords = FloatArray(4)
                    try {
                        frame.transformCoordinates2d(
                            Coordinates2d.IMAGE_NORMALIZED,
                            imgCoords,
                            Coordinates2d.VIEW_NORMALIZED,
                            viewCoords
                        )
                    } catch (e: Exception) {
                        System.arraycopy(imgCoords, 0, viewCoords, 0, 4)
                    }

                    val screenLeft = minOf(viewCoords[0], viewCoords[2])
                    val screenTop = minOf(viewCoords[1], viewCoords[3])
                    val screenRight = maxOf(viewCoords[0], viewCoords[2])
                    val screenBottom = maxOf(viewCoords[1], viewCoords[3])

                    updatedDisplayList.add(
                        DisplayedObject(
                            label = raw.label,
                            labelThai = raw.labelThai,
                            confidence = raw.confidence,
                            screenRect = RectF(screenLeft, screenTop, screenRight, screenBottom),
                            distanceMeters = raw.distanceMeters
                        )
                    )
                }

                mainScope.launch {
                    detectedObjectsState.clear()
                    detectedObjectsState.addAll(updatedDisplayList)
                }

                // 2. Perform object detection at throttled interval (~5 FPS / 200ms)
                val now = System.currentTimeMillis()
                if (now - lastProcessedTime >= 200L) {
                    lastProcessedTime = now
                    frame.acquireCameraImage()?.use { cameraImage ->
                        try {
                            val bitmap = cameraImage.toBitmap()
                            synchronized(frameLock) {
                                pipelineBitmap?.recycle()
                                pipelineBitmap = bitmap
                                hasNewFrame = true
                            }
                            
                            // Trigger async inference
                            inferenceExecutor.execute {
                                runInferenceOnFrame()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to convert camera image to bitmap: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Renderer draw error: ${e.message}", e)
            }
        }

        private fun runInferenceOnFrame() {
            val detector = objectDetector ?: return
            val depth = depthEstimator ?: return

            val bitmap: Bitmap
            synchronized(frameLock) {
                if (!hasNewFrame || pipelineBitmap == null) return
                bitmap = pipelineBitmap!!
                hasNewFrame = false
            }

            val startTime = System.currentTimeMillis()
            
            // Run YOLO detection
            detector.confidenceThreshold = confidenceThreshold
            val detectedList = detector.detect(bitmap, frameLock)
            
            val inferenceTime = System.currentTimeMillis() - startTime

            // Coordinate transformations & Depth sampling (YOLO is in IMAGE_NORMALIZED space)
            val pointsToQuery = mutableListOf<Pair<Float, Float>>()
            
            // For each object, query 5 points (cross pattern) in IMAGE_NORMALIZED space
            for (obj in detectedList) {
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

            // Sync query depths directly using IMAGE_NORMALIZED coordinates
            val allDepths = depth.getDepthAtPoints(pointsToQuery, isNormalizedImageSpace = true)

            // Map and assemble raw detection objects
            val newRawList = mutableListOf<RawDetection>()
            var closestDistance = Float.MAX_VALUE

            for (i in detectedList.indices) {
                val obj = detectedList[i]
                val startIdx = 5 * i
                
                // Sample 5 depths and select minimum positive value
                val objectDepths = allDepths.subList(startIdx, startIdx + 5).filter { it > 0f }
                val measuredDistance = if (objectDepths.isNotEmpty()) objectDepths.minOrNull() ?: -1f else -1f

                if (measuredDistance > 0f) {
                    if (measuredDistance < closestDistance) {
                        closestDistance = measuredDistance
                    }

                    newRawList.add(
                        RawDetection(
                            label = obj.label,
                            labelThai = obj.labelThai,
                            confidence = obj.confidence,
                            boundingBox = obj.boundingBox,
                            distanceMeters = measuredDistance
                        )
                    )
                }
            }

            // Update thread-safe raw detections
            synchronized(rawDetectionsLock) {
                rawDetections.clear()
                rawDetections.addAll(newRawList)
            }

            // Trigger Proximity Vibration alerts
            if (isHapticEnabled && closestDistance < Float.MAX_VALUE) {
                val timeNow = System.currentTimeMillis()
                if (closestDistance < 0.8f) {
                    if (timeNow - lastHapticTime > 1500L) {
                        hapticManager?.vibrateDanger()
                        lastHapticTime = timeNow
                    }
                } else if (closestDistance < 1.5f) {
                    if (timeNow - lastHapticTime > 2500L) {
                        hapticManager?.vibrateWarning()
                        lastHapticTime = timeNow
                    }
                } else if (closestDistance < 3.0f) {
                    if (timeNow - lastHapticTime > 4000L) {
                        hapticManager?.vibrateGeneralInfo()
                        lastHapticTime = timeNow
                    }
                }
            }

            // Calculate FPS & Latency stats for GUI
            frameCounter++
            val timeElapsed = System.currentTimeMillis() - fpsTimer
            var fpsToReport = -1
            if (timeElapsed >= 1000L) {
                fpsToReport = frameCounter
                frameCounter = 0
                fpsTimer = System.currentTimeMillis()
            }

            mainScope.launch {
                if (fpsToReport != -1) {
                    inferenceFpsState = fpsToReport
                }
                yoloLatencyState = inferenceTime
            }
        }
    }

    // ==========================================
    // Jetpack Compose UI Layout
    // ==========================================
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ArCoreYoloScreen() {
        var showSettings by remember { mutableStateOf(false) }
        val sheetState = rememberModalBottomSheetState()
        val context = LocalContext.current

        Box(modifier = Modifier.fillMaxSize()) {
            
            // 1. ARCore live Camera view rendering in background
            if (arCoreActiveState) {
                val session = depthEstimator?.getSession()
                if (session != null) {
                    AndroidView(
                        factory = { ctx ->
                            GLSurfaceView(ctx).apply {
                                setEGLContextClientVersion(2)
                                val renderer = ArRenderer(session)
                                setRenderer(renderer)
                                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            // 2. Translucent Screen Tap coordinate interceptor
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(detectedObjectsState) {
                        detectTapGestures(
                            onTap = { offset ->
                                val width = size.width
                                val height = size.height
                                val nx = offset.x / width
                                val ny = offset.y / height

                                // A. Check if user tapped inside any bounding box overlay
                                val tappedObj = detectedObjectsState.firstOrNull { obj ->
                                    val r = obj.screenRect
                                    nx >= r.left && nx <= r.right && ny >= r.top && ny <= r.bottom
                                }

                                if (tappedObj != null) {
                                    hapticManager?.vibrateGeneralInfo()
                                    val text = if (ttsLanguageState == "th") {
                                        "${tappedObj.labelThai} ห่าง ${String.format(Locale.US, "%.1f", tappedObj.distanceMeters)} เมตร"
                                    } else {
                                        "${tappedObj.label} at ${String.format(Locale.US, "%.1f", tappedObj.distanceMeters)} meters"
                                    }
                                    ttsManager?.speak(text, TextToSpeech.QUEUE_FLUSH)
                                } else {
                                    // B. If no box hit, sample exact physical depth at tap point
                                    Thread {
                                        val sampledDepthList = depthEstimator?.getDepthAtPoints(listOf(nx to ny))
                                        val depth = sampledDepthList?.firstOrNull() ?: -1f
                                        mainScope.launch {
                                            if (depth > 0f) {
                                                hapticManager?.vibrateGeneralInfo()
                                                val text = if (ttsLanguageState == "th") {
                                                    "ระยะจุดนี้ ${String.format(Locale.US, "%.1f", depth)} เมตร"
                                                } else {
                                                    "Distance here is ${String.format(Locale.US, "%.1f", depth)} meters"
                                                }
                                                ttsManager?.speak(text, TextToSpeech.QUEUE_FLUSH)
                                            } else {
                                                hapticManager?.vibrateWarning()
                                                ttsManager?.speak(
                                                    if (ttsLanguageState == "th") "ไม่สามารถวัดจุดนี้ได้" 
                                                    else "Cannot measure distance here", 
                                                    TextToSpeech.QUEUE_FLUSH
                                                )
                                            }
                                        }
                                    }.start()
                                }
                            }
                        )
                    }
            )

            // 3. Canvas overlay drawing bounding boxes, names, and distances
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                detectedObjectsState.forEach { obj ->
                    val r = obj.screenRect
                    val left = r.left * w
                    val top = r.top * h
                    val right = r.right * w
                    val bottom = r.bottom * h
                    
                    // Danger items (under 1.2 meters) highlighted in Coral Red, otherwise Emerald Green
                    val isDanger = obj.distanceMeters < 1.2f
                    val boxColor = if (isDanger) Color(0xFFEF4444) else Color(0xFF10B981)

                    // Draw Bounding box rectangle
                    drawRect(
                        color = boxColor,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = 3.dp.toPx())
                    )

                    // Draw translucent label badge background
                    val labelText = if (ttsLanguageState == "th") {
                        "${obj.labelThai} (${String.format(Locale.US, "%.1f", obj.distanceMeters)}m)"
                    } else {
                        "${obj.label} (${String.format(Locale.US, "%.1f", obj.distanceMeters)}m)"
                    }
                    
                    // Calculate quick approximate text dimensions for badge sizing
                    val badgeW = (labelText.length * 8.dp.toPx()).coerceAtLeast(60.dp.toPx())
                    val badgeH = 24.dp.toPx()

                    drawRect(
                        color = boxColor.copy(alpha = 0.85f),
                        topLeft = Offset(left, (top - badgeH).coerceAtLeast(0f)),
                        size = Size(badgeW, badgeH)
                    )

                    // Compose Canvas text is drawn using native Paint for simplicity and performance inside canvas
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 12.sp.toPx()
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        labelText,
                        left + 6.dp.toPx(),
                        (top - 6.dp.toPx()).coerceAtLeast(14.dp.toPx()),
                        paint
                    )
                }

                // 4. Center Screen Crosshair
                val cx = w / 2f
                val cy = h / 2f
                val crosshairColor = if (centerDistanceState in 0.1f..1.2f) Color(0xFFEF4444) else Color.White
                
                // Draw thin circle crosshair
                drawCircle(
                    color = crosshairColor.copy(alpha = 0.5f),
                    radius = 16.dp.toPx(),
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.dp.toPx())
                )
                // Draw center point dot
                drawCircle(
                    color = crosshairColor,
                    radius = 2.dp.toPx(),
                    center = Offset(cx, cy)
                )

                // Write center distance value overlay
                if (centerDistanceState > 0f) {
                    val centerText = "${String.format(Locale.US, "%.1f", centerDistanceState)}m"
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 14.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                        // Add shadow/border effect for visual clarity
                        setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        centerText,
                        cx,
                        cy + 36.dp.toPx(),
                        paint
                    )
                }
            }

            // 5. Glassmorphic HUD overlay (Latency, Latency, FPS, Status)
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Top header bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "ARCORE + YOLO DISTANCE",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (arCoreActiveState) Color(0xFF10B981) else Color(0xFFF59E0B))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = arCoreStatusState,
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Settings toggle icon
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Open Settings",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatBadge(label = "YOLO Nano", value = "${yoloLatencyState}ms", modifier = Modifier.weight(1f))
                    StatBadge(label = "Inference Rate", value = "${inferenceFpsState} FPS", modifier = Modifier.weight(1f))
                    StatBadge(label = "Objects Detected", value = "${detectedObjectsState.size}", modifier = Modifier.weight(1f))
                }
            }

            // 6. Proximity overlay flash (subtle warning flash)
            val hasDangerObj = detectedObjectsState.any { it.distanceMeters < 0.8f }
            AnimatedVisibility(
                visible = hasDangerObj,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(4.dp, Color(0xFFEF4444).copy(alpha = 0.6f))
                )
            }

            // 7. Interactive Bottom Settings Panel
            if (showSettings) {
                ModalBottomSheet(
                    onDismissRequest = { showSettings = false },
                    sheetState = sheetState,
                    containerColor = MaterialTheme.colorScheme.surface,
                    dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
                ) {
                    SettingsPanel(
                        onDismiss = { showSettings = false }
                    )
                }
            }
        }
    }

    @Composable
    fun StatBadge(label: String, value: String, modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(vertical = 8.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(text = value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        }
    }

    @Composable
    fun SettingsPanel(onDismiss: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = "การตั้งค่า (Settings)",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Switch: Text-to-Speech voice alerts
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("เสียงบรรยายรายละเอียดวัตถุ", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("พูดบรรยายชื่อของและระยะทางอัตโนมัติ", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = isTtsEnabled,
                    onCheckedChange = { isTtsEnabled = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                )
            }

            // Switch: Proximity Haptic Vibrations
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("สั่นแจ้งเตือนระยะสิ่งกีดขวาง", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("สั่นแรงขึ้นเมื่อวัตถุอยู่ใกล้เกินเกณฑ์", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = isHapticEnabled,
                    onCheckedChange = { isHapticEnabled = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                )
            }

            // Slider: Confidence Threshold
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("เกณฑ์ความเชื่อมั่นการคัดกรอง", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(String.format(Locale.US, "%.2f", confidenceThreshold), color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = confidenceThreshold,
                    onValueChange = { confidenceThreshold = it },
                    valueRange = 0.20f..0.85f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        thumbColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // Slider: Speech interval
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("ความถี่ในการพูดบรรยายสิ่งของ", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("ทุก $speechIntervalSeconds วินาที", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = speechIntervalSeconds.toFloat(),
                    onValueChange = { speechIntervalSeconds = it.toInt() },
                    valueRange = 2f..10f,
                    steps = 8,
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        thumbColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // Tab Row: Language Select
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text("ภาษาเสียงบรรยาย (TTS Language)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                TabRow(
                    selectedTabIndex = if (ttsLanguageState == "th") 0 else 1,
                    containerColor = Color.Black.copy(alpha = 0.2f),
                    indicator = { tabPositions ->
                        TabRowDefaults.Indicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[if (ttsLanguageState == "th") 0 else 1]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                ) {
                    Tab(
                        selected = ttsLanguageState == "th",
                        onClick = {
                            ttsLanguageState = "th"
                            ttsManager?.setLanguage("th")
                            ttsManager?.speak("เปลี่ยนภาษาเป็นภาษาไทย")
                        },
                        text = { Text("ภาษาไทย", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = ttsLanguageState == "en",
                        onClick = {
                            ttsLanguageState = "en"
                            ttsManager?.setLanguage("en")
                            ttsManager?.speak("Language changed to English")
                        },
                        text = { Text("English", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("ปิดการตั้งค่า", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}
