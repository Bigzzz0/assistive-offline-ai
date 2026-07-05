package com.assistive.system

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import com.assistive.system.logging.AppLogger as Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.KeyEvent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.assistive.system.download.ModelDownloader
import com.assistive.system.download.ModelFile
import com.assistive.system.download.ModelManager
import com.assistive.system.logging.AppLogger
import com.assistive.system.monitoring.PerformanceMetrics
import com.assistive.system.service.AssistiveService
import com.assistive.system.vision.DepthEstimator
import com.assistive.system.vision.DistancePipeline
import com.assistive.system.vision.DistanceResult
import com.assistive.system.vision.ObjectDetector
import com.assistive.system.vision.VisionPipeline
import com.assistive.system.vision.BackgroundRenderer
import com.assistive.system.vision.toBitmap
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.opengl.GLSurfaceView
import android.opengl.GLES20
import android.opengl.GLES11Ext
import android.view.WindowManager
import com.google.ar.core.Session
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : ComponentActivity() {

    private var assistiveService by mutableStateOf<AssistiveService?>(null)
    private var isBound by mutableStateOf(false)
    private var visionPipeline: VisionPipeline? = null
    private lateinit var cameraExecutor: ExecutorService

    // ─── ARCore + Object Detection ──────────────────────────────────────────
    private var objectDetector: ObjectDetector? = null
    private var depthEstimator: DepthEstimator? = null
    private var distancePipeline: DistancePipeline? = null
    private var isArInitialized by mutableStateOf(false)
    private var isArActiveState by mutableStateOf(false)
    private var glSurfaceView: GLSurfaceView? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AssistiveService.LocalBinder
            val s = binder.getService()
            assistiveService = s
            isBound = true
            Log.i("MainActivity", "Connected to AssistiveService.")
            
            // Attach distance pipeline if it is already initialized
            distancePipeline?.let { pipeline ->
                s.attachDistancePipeline(pipeline)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            assistiveService = null
            isBound = false
            Log.i("MainActivity", "Disconnected from AssistiveService.")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (cameraGranted && audioGranted) {
            startAssistiveService()
        } else {
            Log.e("MainActivity", "Permissions not granted by user.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(applicationContext)
        cameraExecutor = Executors.newSingleThreadExecutor()

        checkPermissionsAndStart()
        initializeArCorePipeline()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF10B981),   // Emerald
                    secondary = Color(0xFF3B82F6), // Blue
                    background = Color.Black,       // True Black for high-contrast
                    surface = Color(0xFF111111)     // High-contrast surface
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AssistiveMainScreen()
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (cameraOk && audioOk) {
            startAssistiveService()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun startAssistiveService() {
        val intent = Intent(this, AssistiveService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        try {
            depthEstimator?.onResume()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to resume ARCore session: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            depthEstimator?.onPause()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to pause ARCore session: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        visionPipeline?.shutdown()
        cameraExecutor.shutdown()
        releaseArCorePipeline()
    }

    inner class ArCoreRenderer(
        private val glSurfaceView: GLSurfaceView,
        private val session: Session
    ) : GLSurfaceView.Renderer {
        
        private val backgroundRenderer = BackgroundRenderer()
        private var lastProcessedTime = 0L

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
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
            val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display?.rotation ?: 0
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
            session.setDisplayGeometry(rotation, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val session = session
            try {
                val frame = session.update()
                backgroundRenderer.draw(frame)
                
                val depthImage = try {
                    frame.acquireDepthImage16Bits()
                } catch (e: Exception) {
                    null
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
                    } catch (e: Exception) {
                        Log.w("ArCoreRenderer", "Failed to extract depth buffer: ${e.message}")
                    } finally {
                        depthImage.close()
                    }
                } else {
                    depthEstimator?.updateDepthBuffer(frame, null, 0, 0, 0, 0)
                }
                
                val now = System.currentTimeMillis()
                if (now - lastProcessedTime >= 200L) {
                    lastProcessedTime = now
                    val cameraImage = try {
                        frame.acquireCameraImage()
                    } catch (e: Exception) {
                        null
                    }
                    if (cameraImage != null) {
                        try {
                            val bitmap = cameraImage.toBitmap()
                            cameraExecutor.execute {
                                try {
                                    visionPipeline?.processBitmapFromArCore(bitmap)
                                } catch (e: Exception) {
                                    Log.w("ArCoreRenderer", "Failed to process ARCore frame: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("ArCoreRenderer", "Failed to convert camera frame to bitmap: ${e.message}")
                        } finally {
                            cameraImage.close()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ArCoreRenderer", "onDrawFrame failed: ${e.message}", e)
            }
        }
    }

    /**
     * Initialize ARCore + ObjectDetector + DistancePipeline on a background thread.
     *
     * Works in three degraded modes:
     *  Mode 1 (full):       OD model + ARCore Depth → labels + real depth
     *  Mode 2 (depth-only): No OD model + ARCore Depth → "สิ่งกีดขวาง" + real depth
     *  Mode 3 (heuristic):  OD model + no ARCore → labels + estimated depth
     *
     * Never returns without creating a DistancePipeline if ARCore is at all accessible.
     */
    private fun initializeArCorePipeline() {
        if (isArInitialized) return
        Thread {
            try {
                // Step 1: Try to load OD model (optional — degrades gracefully)
                var detector: ObjectDetector? = null
                try {
                    val d = ObjectDetector(applicationContext)
                    if (d.initialize()) {
                        detector = d
                        Log.i("MainActivity", "ObjectDetector loaded successfully")
                    } else {
                        d.release()
                        Log.w("MainActivity", "ObjectDetector not available — running depth-only mode")
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "ObjectDetector skipped (${e.message}) — depth-only mode")
                }

                // Step 2: Initialize DepthEstimator (ARCore) — always attempt
                val depth = DepthEstimator()
                
                // Run ARCore availability check and session initialization on UI Thread
                var arcoreReady = false
                val latch = java.util.concurrent.CountDownLatch(1)
                runOnUiThread {
                    try {
                        arcoreReady = depth.checkAndInstallArCore(this@MainActivity)
                        if (arcoreReady) {
                            depth.initialize(this@MainActivity)
                            // Resume the ARCore session immediately to prevent race conditions where Activity's onResume has already executed
                            depth.onResume()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "ARCore UI thread check failed: ${e.message}")
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await()

                if (arcoreReady && depth.isRealArCoreActive) {
                    Log.i("MainActivity", "ARCore Depth API initialized")
                    runOnUiThread {
                        isArActiveState = true
                    }
                } else {
                    val errMsg = depth.arCoreErrorMessage ?: "อุปกรณ์ไม่รองรับหรือยังไม่ได้ติดตั้ง ARCore"
                    Log.w("MainActivity", "ARCore not available: $errMsg")
                    runOnUiThread {
                        isArActiveState = false
                        assistiveService?.audioPipeline?.speak("คำเตือน ระบบวัดระยะด้วยเออาร์คอร์ไม่สามารถทำงานได้ เนื่องจาก $errMsg")
                    }
                }

                // Step 3: Always create DistancePipeline regardless of OD/ARCore status
                val pipeline = DistancePipeline(
                    context = applicationContext,
                    objectDetector = detector,   // null = depth-only fallback
                    depthEstimator = depth,
                    onDistanceUpdate = { results ->
                        assistiveService?.onDistanceUpdate(results)
                    }
                )

                // Pre-initialize VisionPipeline on UI thread if not already set up
                if (visionPipeline == null) {
                    runOnUiThread {
                        if (visionPipeline == null) {
                            visionPipeline = VisionPipeline(
                                context = applicationContext,
                                lifecycleOwner = this@MainActivity,
                                isFrameRequested = { assistiveService?.hasPendingPrompt() == true },
                                onSceneChanged = { jpegBytes ->
                                    assistiveService?.onCameraFrameAvailable(jpegBytes)
                                }
                            )
                        }
                        visionPipeline?.distancePipeline = pipeline
                    }
                } else {
                    visionPipeline?.distancePipeline = pipeline
                }

                objectDetector = detector
                depthEstimator = depth
                distancePipeline = pipeline
                isArInitialized = true

                // Attach to service if already bound
                assistiveService?.attachDistancePipeline(pipeline)

                val mode = when {
                    detector != null && depth.isDepthAvailable() -> "OD + ARCore Depth (full)"
                    detector == null && depth.isDepthAvailable()  -> "ARCore Depth-only"
                    detector != null                              -> "OD + heuristic depth"
                    else                                          -> "heuristic depth only"
                }
                Log.i("MainActivity", "DistancePipeline started in mode: $mode")
            } catch (e: Exception) {
                Log.e("MainActivity", "ARCore pipeline initialization failed: ${e.message}", e)
            }
        }.start()
    }

    private fun releaseArCorePipeline() {
        try { distancePipeline?.release() } catch (ignored: Exception) {}
        try { objectDetector?.release() } catch (ignored: Exception) {}
        try { depthEstimator?.release() } catch (ignored: Exception) {}
        distancePipeline = null; objectDetector = null; depthEstimator = null
        isArInitialized = false
        isArActiveState = false
        glSurfaceView = null
        Log.i("MainActivity", "ARCore pipeline released")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isModelLoaded = isBound && assistiveService?.inferenceEngine?.isInitialized() == true
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            Log.i("MainActivity", "Volume Up pressed - triggering OCR")
            if (isModelLoaded) {
                assistiveService?.hapticManager?.vibrateGeneralInfo()
                assistiveService?.handleVoiceCommand("อ่าน")
            } else {
                assistiveService?.hapticManager?.vibrateWarning()
            }
            return true
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            Log.i("MainActivity", "Volume Down pressed - triggering Object ID")
            if (isModelLoaded) {
                assistiveService?.hapticManager?.vibrateGeneralInfo()
                assistiveService?.handleVoiceCommand("ดู")
            } else {
                assistiveService?.hapticManager?.vibrateWarning()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ========================================
    // MAIN SCREEN COMPOSABLE
    // ========================================
    @Composable
    fun AssistiveMainScreen() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        // (coroutineScope is used in ModelManagerPanel)

        // ---- Disclaimer dialog (shown once per app session) ----
        var disclaimerShown by remember { mutableStateOf(false) }
        if (!disclaimerShown) {
            AlertDialog(
                onDismissRequest = {},
                containerColor = Color(0xFF1E293B),
                title = {
                    Text("⚠️ ข้อควรระวัง", color = Color.White, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        "ระบบนี้เป็นเครื่องมือช่วยเหลือเสริม ไม่ได้ออกแบบมาเพื่อการนำทางโดยตรง\n\n" +
                        "อาจให้ข้อมูลไม่ถูกต้องในบางสถานการณ์ กรุณาใช้วิจารณญาณร่วมด้วยและใช้ไม้เท้าหรืออุปกรณ์ช่วยเหลืออื่นร่วมกัน",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { disclaimerShown = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "รับทราบข้อควรระวัง" }
                    ) {
                        Text("รับทราบและเข้าใจแล้ว", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            )
            return
        }

        // ---- State bindings ----
        val statusText by if (isBound && assistiveService != null) {
            assistiveService!!.serviceStatus.collectAsState()
        } else {
            remember { mutableStateOf("กำลังรอการเชื่อมต่อบริการ...") }
        }

        val aiResultText by if (isBound && assistiveService != null) {
            assistiveService!!.inferenceOutput.collectAsState()
        } else {
            remember { mutableStateOf("") }
        }

        val perfMetrics by if (isBound && assistiveService != null) {
            assistiveService!!.performanceMonitor.metrics.collectAsState()
        } else {
            remember { mutableStateOf(PerformanceMetrics()) }
        }

        val currentlyAnalyzingBitmap by if (isBound && assistiveService != null) {
            assistiveService!!.currentlyAnalyzingBitmap.collectAsState()
        } else {
            remember { mutableStateOf<Bitmap?>(null) }
        }

        val distanceResults by if (isBound && assistiveService != null) {
            assistiveService!!.distanceResults.collectAsState()
        } else {
            remember { mutableStateOf(emptyList<DistanceResult>()) }
        }

        val isModelLoaded = remember(isBound, assistiveService, statusText) {
            isBound && assistiveService?.inferenceEngine?.isInitialized() == true
        }

        var showDevPanel by remember { mutableStateOf(false) }
        var showModelManager by remember { mutableStateOf(false) }

        var currentMode by remember { mutableStateOf(ActiveMode.OBJECT) }
        val announceMode = { mode: ActiveMode ->
            assistiveService?.hapticManager?.vibrateGeneralInfo()
            assistiveService?.audioPipeline?.speak(mode.speech)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragEnd = {
                            if (totalDrag > 150f) {
                                currentMode = currentMode.previous()
                                announceMode(currentMode)
                            } else if (totalDrag < -150f) {
                                currentMode = currentMode.next()
                                announceMode(currentMode)
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            totalDrag += dragAmount
                        }
                    )
                }
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- Header with mode badge ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ASSISTIVE OFFLINE AI",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                // Mode badge
                val isRealMode = perfMetrics.isVlmReal || perfMetrics.isAsrReal
                Surface(
                    color = if (perfMetrics.isVlmReal && perfMetrics.isAsrReal) Color(0xFF10B981)
                            else if (isRealMode) Color(0xFFF59E0B)
                            else Color(0xFF6B7280),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = when {
                            perfMetrics.isVlmReal && perfMetrics.isAsrReal -> "✅ Real Mode"
                            isRealMode -> "⚡ Partial"
                            else -> "⚠️ Mock"
                        },
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Active Mode Banner ----
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(2.dp, Color(0xFF3B82F6)),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "โหมดใช้งานสัมผัสปัจจุบัน: ${currentMode.speech}"
                    }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "โหมดใช้งานสัมผัสปัจจุบัน",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        currentMode.label,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "ปัดซ้าย/ขวาเพื่อเปลี่ยน | แตะสองครั้งที่กล้องเพื่อทำงาน",
                        color = Color(0xFF60A5FA),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Camera Viewport ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .pointerInput(currentMode, isModelLoaded) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (isModelLoaded) {
                                    assistiveService?.hapticManager?.vibrateGeneralInfo()
                                    assistiveService?.handleVoiceCommand(currentMode.command)
                                } else {
                                    assistiveService?.hapticManager?.vibrateWarning()
                                }
                            },
                            onTap = { offset ->
                                // Tap to measure exact distance at the tapped coordinate
                                val nx = offset.x / size.width
                                val ny = offset.y / size.height
                                val tappedDepth = depthEstimator?.getDepthAtNormalizedPoint(nx, ny) ?: -1f
                                if (tappedDepth > 0f) {
                                    assistiveService?.hapticManager?.vibrateGeneralInfo()
                                    val distStr = String.format(java.util.Locale.US, "%.1f", tappedDepth)
                                    assistiveService?.audioPipeline?.speak("ระยะ $distStr เมตร")
                                } else {
                                    assistiveService?.hapticManager?.vibrateWarning()
                                    assistiveService?.audioPipeline?.speak("ไม่สามารถวัดระยะจุดนี้ได้")
                                }
                            }
                        )
                    }
                    .semantics {
                        contentDescription = "หน้าต่างกล้อง โหมดปัจจุบันคือ ${currentMode.speech}. แตะสองครั้งเพื่อเริ่มสแกน หรือปัดซ้ายขวาเพื่อเปลี่ยนโหมด"
                    }
            ) {
                val arActive = isArActiveState
                if (arActive) {
                    val session = depthEstimator?.getSession()
                    if (session != null) {
                        AndroidView(
                            factory = { ctx ->
                                GLSurfaceView(ctx).apply {
                                    setEGLContextClientVersion(2)
                                    val renderer = ArCoreRenderer(this, session)
                                    setRenderer(renderer)
                                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                                    glSurfaceView = this
                                    onResume() // Start GL rendering loop immediately
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        
                        // Dynamically bind GLSurfaceView to Activity's lifecycle events
                        val lifecycle = LocalLifecycleOwner.current.lifecycle
                        DisposableEffect(lifecycle) {
                            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                when (event) {
                                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                                        glSurfaceView?.onResume()
                                    }
                                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                                        glSurfaceView?.onPause()
                                    }
                                    else -> {}
                                }
                            }
                            lifecycle.addObserver(observer)
                            onDispose {
                                lifecycle.removeObserver(observer)
                                glSurfaceView = null
                            }
                        }
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                if (visionPipeline == null) {
                                    visionPipeline = VisionPipeline(
                                        context = ctx,
                                        lifecycleOwner = lifecycleOwner,
                                        isFrameRequested = { assistiveService?.hasPendingPrompt() == true },
                                        onSceneChanged = { jpegBytes ->
                                            assistiveService?.onCameraFrameAvailable(jpegBytes)
                                        }
                                    )
                                }
                                visionPipeline?.distancePipeline = distancePipeline

                                @Suppress("DEPRECATION")
                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setTargetResolution(android.util.Size(640, 480))
                                    .build()
                                imageAnalysis.setAnalyzer(cameraExecutor, visionPipeline!!.getAnalyzer())

                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageAnalysis
                                    )
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Camera binding failed: ${e.message}")
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // Interactive controls overlay
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = {
                            assistiveService?.hapticManager?.vibrateGeneralInfo()
                            val centerDepth = depthEstimator?.getDepthAtNormalizedPoint(0.5f, 0.5f) ?: -1f
                            if (centerDepth > 0f) {
                                val distStr = String.format(java.util.Locale.US, "%.1f", centerDepth)
                                assistiveService?.audioPipeline?.speak("ระยะตรงกลาง $distStr เมตร")
                            } else {
                                val errMsg = depthEstimator?.arCoreErrorMessage ?: "ระบบไม่สามารถวัดระยะได้ในขณะนี้"
                                assistiveService?.audioPipeline?.speak("ไม่สามารถวัดระยะตรงกลางได้ เนื่องจาก $errMsg")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text("🎯 แตะเพื่อวัดระยะตรงกลาง", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    
                    Text(
                        text = "แตะเพื่อวัดระยะจุดนั้นๆ | ดับเบิ้ลแตะเพื่อประมวลผลคำสั่ง",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (distanceResults.isNotEmpty()) {
                    ArDistanceOverlay(
                        results = distanceResults,
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                }

                currentlyAnalyzingBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "ภาพที่กำลังถูกวิเคราะห์",
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                    // Visual indicator/overlay that it's analyzing
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        // Close / Cancel Button in Top Right
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .size(40.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .clickable {
                                    assistiveService?.cancelCurrentAnalysis()
                                }
                                .semantics { contentDescription = "ยกเลิกการประมวลผล" },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✕",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF10B981))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "กำลังวิเคราะห์ภาพนี้...",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Status Card ----
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "สถานะระบบ: $statusText${if (perfMetrics.lastInferenceLatencyMs > 0) " การประมวลผลล่าสุดใช้เวลา ${perfMetrics.lastInferenceLatencyMs} มิลลิวินาที" else ""}"
                    }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("สถานะระบบ", color = Color.LightGray, fontSize = 12.sp)
                        Text(
                            statusText,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    // Quick perf badge
                    if (perfMetrics.lastInferenceLatencyMs > 0) {
                        Text(
                            "${perfMetrics.lastInferenceLatencyMs}ms",
                            color = if (perfMetrics.lastInferenceLatencyMs < 3000) Color(0xFF10B981) else Color(0xFFEF4444),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Dedicated ARCore Obstacle Distance Panel ----
            val closestObstacle = distanceResults.firstOrNull()
            val obstacleDistance = closestObstacle?.distanceMeters ?: -1f
            val obstacleLabel = closestObstacle?.labelThai ?: "สิ่งกีดขวาง/กำแพง"
            val isArCoreActive = depthEstimator?.isRealArCoreActive == true
            val arCoreError = depthEstimator?.arCoreErrorMessage

            val distanceColor = when {
                !isArCoreActive -> Color(0xFFEF4444) // Red for unavailable
                obstacleDistance <= 0f -> Color.Gray
                obstacleDistance < 0.8f -> Color(0xFFEF4444) // Red
                obstacleDistance < 1.5f -> Color(0xFFF59E0B) // Amber
                obstacleDistance < 3.0f -> Color(0xFFEAB308) // Yellow
                else -> Color(0xFF10B981) // Green
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(2.dp, distanceColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (!isArCoreActive) {
                            "ไม่สามารถวัดระยะทางด้วย ARCore ได้: ${arCoreError ?: "ไม่ทราบสาเหตุ"}"
                        } else {
                            "ระยะห่างจากสิ่งกีดขวางด้านหน้า: ${String.format(java.util.Locale.US, "%.1f", obstacleDistance)} เมตร"
                        }
                    }
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🧱 ระยะห่างสิ่งกีดขวาง / กำแพง (ARCore)",
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (!isArCoreActive) {
                        Text(text = "❌ ARCore ไม่พร้อมใช้งาน", color = Color(0xFFEF4444), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = arCoreError ?: "ไม่ทราบสาเหตุ หรือสลับไปทำงานบน fallback สำรอง",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        val centerDistance = depthEstimator?.getDepthAtNormalizedPoint(0.5f, 0.5f) ?: -1f
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Column 1: Center Point
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🎯 ตรงกลางภาพ", color = Color.LightGray, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                val centerColor = when {
                                    centerDistance <= 0f -> Color.Gray
                                    centerDistance < 0.8f -> Color(0xFFEF4444)
                                    centerDistance < 1.5f -> Color(0xFFF59E0B)
                                    else -> Color(0xFF10B981)
                                }
                                Text(
                                    text = if (centerDistance > 0f) String.format(java.util.Locale.US, "%.1f ม.", centerDistance) else "N/A",
                                    color = centerColor,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            
                            // Vertical Divider
                            Box(modifier = Modifier.width(1.dp).height(40.dp).background(Color.Gray.copy(alpha = 0.5f)))
                            
                            // Column 2: Closest Obstacle
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("⚠️ ใกล้ที่สุด", color = Color.LightGray, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                val closestColor = when {
                                    obstacleDistance <= 0f -> Color.Gray
                                    obstacleDistance < 0.8f -> Color(0xFFEF4444)
                                    obstacleDistance < 1.5f -> Color(0xFFF59E0B)
                                    else -> Color(0xFF10B981)
                                }
                                Text(
                                    text = if (obstacleDistance > 0f) String.format(java.util.Locale.US, "%.1f ม.", obstacleDistance) else "N/A",
                                    color = closestColor,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        
                        if (closestObstacle != null && obstacleDistance > 0f) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "วัตถุที่ใกล้: $obstacleLabel",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- AI Result Output ----
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(2.dp, Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .clickable {
                        assistiveService?.hapticManager?.vibrateGeneralInfo()
                        if (aiResultText.isNotEmpty()) {
                            assistiveService?.audioPipeline?.speak(aiResultText)
                        }
                    }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "คำอธิบายภาพจากเอไอ: ${if (aiResultText.isEmpty()) "ยังไม่มีการวิเคราะห์ สั่งการด้วยเสียงหรือแตะกล้องเพื่อเริ่ม" else aiResultText}"
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (aiResultText.isEmpty())
                            "ระบบจะอธิบายภาพผ่านเสียงพูดและการสั่น\nพูดว่า \"ดู\", \"อ่าน\" หรือแตะกล้องเพื่อเริ่ม"
                        else aiResultText,
                        color = if (aiResultText.isEmpty()) Color.Gray else Color.White,
                        fontSize = if (aiResultText.isEmpty()) 16.sp else 20.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ---- Stacked Big Action buttons ----
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        assistiveService?.hapticManager?.vibrateGeneralInfo()
                        assistiveService?.handleVoiceCommand("อ่าน")
                    },
                    enabled = isModelLoaded,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1D4ED8),
                        disabledContainerColor = Color(0xFF1D4ED8).copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .semantics { contentDescription = "ปุ่มขนาดใหญ่สำหรับอ่านข้อความภาษาไทย" }
                ) {
                    Text("📖 อ่านข้อความ (OCR)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (isModelLoaded) Color.White else Color.White.copy(alpha = 0.6f))
                }
                Button(
                    onClick = {
                        assistiveService?.hapticManager?.vibrateGeneralInfo()
                        assistiveService?.handleVoiceCommand("ดู")
                    },
                    enabled = isModelLoaded,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF059669),
                        disabledContainerColor = Color(0xFF059669).copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .semantics { contentDescription = "ปุ่มขนาดใหญ่สำหรับสแกนระบุสิ่งของ" }
                ) {
                    Text("👁️ ดูสิ่งของบนโต๊ะ", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (isModelLoaded) Color.White else Color.White.copy(alpha = 0.6f))
                }
                Button(
                    onClick = {
                        assistiveService?.hapticManager?.vibrateGeneralInfo()
                        assistiveService?.handleVoiceCommand("ข้างหน้า")
                    },
                    enabled = isModelLoaded,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB45309),
                        disabledContainerColor = Color(0xFFB45309).copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .semantics { contentDescription = "ปุ่มขนาดใหญ่สำหรับตรวจสอบสิ่งกีดขวางข้างหน้า" }
                ) {
                    Text("🚧 ตรวจสิ่งกีดขวางข้างหน้า", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (isModelLoaded) Color.White else Color.White.copy(alpha = 0.6f))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---- Dev Panel + Model Manager toggles ----
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        assistiveService?.hapticManager?.vibrateGeneralInfo()
                        showDevPanel = !showDevPanel
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (showDevPanel) "ซ่อน Dev" else "📊 Dev Panel", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = {
                        assistiveService?.hapticManager?.vibrateGeneralInfo()
                        showModelManager = !showModelManager
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (showModelManager) "ซ่อนโมเดล" else "⬇️ จัดการโมเดล", fontSize = 12.sp) }
            }

            // ---- Performance Stats Panel ----
            if (showDevPanel) {
                Spacer(modifier = Modifier.height(12.dp))
                PerformancePanel(perfMetrics)
                Spacer(modifier = Modifier.height(8.dp))
                DeveloperPanel()
            }

            // ---- Model Manager Panel ----
            if (showModelManager) {
                Spacer(modifier = Modifier.height(12.dp))
                ModelManagerPanel(context)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ========================================
    // PERFORMANCE STATS PANEL
    // ========================================
    @Composable
    fun PerformancePanel(metrics: PerformanceMetrics) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2039)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "แผงแสดงประสิทธิภาพการทำงาน ตัวชี้วัดรวม ${metrics.totalInferenceCount} ครั้ง เฉลี่ยครั้งละ ${metrics.averageLatencyMs} มิลลิวินาที"
                }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "📈 Performance Metrics (Proposal §6.1)",
                    color = Color(0xFF38BDF8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Inference Latency
                PerformanceMetricRow(
                    label = "Inference Latency",
                    valueText = "${metrics.lastInferenceLatencyMs} ms",
                    progress = (metrics.lastInferenceLatencyMs / 5000f).coerceIn(0f, 1f),
                    color = if (metrics.lastInferenceLatencyMs < 3000) Color(0xFF10B981) else Color(0xFFEF4444),
                    contentDescription = "เวลาประมวลผลเอไอภาพล่าสุด: ${metrics.lastInferenceLatencyMs} มิลลิวินาที, สถานะ: ${if (metrics.lastInferenceLatencyMs < 3000) "ปกติ" else "ช้ากว่าปกติ"}"
                )

                // Token Rate
                PerformanceMetricRow(
                    label = "Token Rate",
                    valueText = "${"%.1f".format(metrics.tokenRatePerSec)} t/s",
                    progress = (metrics.tokenRatePerSec / 15f).coerceIn(0f, 1f),
                    color = if (metrics.tokenRatePerSec >= 8f || !metrics.isVlmReal) Color(0xFF10B981) else Color(0xFFF59E0B),
                    contentDescription = "ความเร็วสร้างข้อความท็อกเกน: ${"%.1f".format(metrics.tokenRatePerSec)} ท็อกเกนต่อวินาที"
                )

                // Memory Usage
                PerformanceMetricRow(
                    label = "Memory Usage",
                    valueText = "${metrics.memoryUsageMB} MB",
                    progress = (metrics.memoryUsageMB / 6000f).coerceIn(0f, 1f),
                    color = if (metrics.memoryUsageMB < 4000) Color(0xFF10B981) else Color(0xFFEF4444),
                    contentDescription = "ปริมาณการใช้หน่วยความจำแรม: ${metrics.memoryUsageMB} เมกะไบต์"
                )

                // Temperature
                PerformanceMetricRow(
                    label = "Temperature",
                    valueText = "${"%.0f".format(metrics.temperatureCelsius)} °C",
                    progress = (metrics.temperatureCelsius / 60f).coerceIn(0f, 1f),
                    color = if (metrics.temperatureCelsius < 45f) Color(0xFF10B981) else if (metrics.temperatureCelsius < 55f) Color(0xFFF59E0B) else Color(0xFFEF4444),
                    contentDescription = "อุณหภูมิหน่วยประมวลผล: ${"%.0f".format(metrics.temperatureCelsius)} องศาเซลเซียส, สถานะ: ${if (metrics.temperatureCelsius < 45f) "ปกติ" else "ร้อนกว่าปกติ"}"
                )

                // Battery Drain
                PerformanceMetricRow(
                    label = "Battery Drain",
                    valueText = "${"%.1f".format(metrics.batteryDrainMahPerMin)} mAh/min",
                    progress = (metrics.batteryDrainMahPerMin / 20f).coerceIn(0f, 1f),
                    color = if (metrics.batteryDrainMahPerMin < 15f) Color(0xFF10B981) else Color(0xFFEF4444),
                    contentDescription = "อัตราสิ้นเปลืองพลังงานแบตเตอรี่: ${"%.1f".format(metrics.batteryDrainMahPerMin)} มิลลิแอมป์ต่อนาที"
                )

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                        contentDescription = "การประมวลผลรวมทั้งหมด ${metrics.totalInferenceCount} ครั้ง ค่าเฉลี่ย ${metrics.averageLatencyMs} มิลลิวินาที"
                    },
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total Inferences", color = Color.LightGray, fontSize = 12.sp)
                    Text(
                        "${metrics.totalInferenceCount} (avg ${metrics.averageLatencyMs}ms)",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("VLM: ${if (metrics.isVlmReal) "Gemma Real (${metrics.activeBackend}) ✅" else "Mock ⚠️"}", color = Color.LightGray, fontSize = 11.sp)
                    Text("ASR: ${if (metrics.isAsrReal) "Sherpa Real ✅" else "Mock ⚠️"}", color = Color.LightGray, fontSize = 11.sp)
                }
            }
        }
    }

    // ========================================
    // MODEL MANAGER PANEL
    // ========================================
    @Composable
    fun ModelManagerPanel(context: Context) {
        val coroutineScope = rememberCoroutineScope()
        val modelManager = remember { ModelManager(context) }
        var asrDownloadProgress by remember { mutableStateOf(-1f) }
        var asrStatusText by remember { mutableStateOf("") }
        var vlmDownloadProgress by remember { mutableStateOf(-1f) }
        var vlmStatusText by remember { mutableStateOf("") }
        var isDownloading by remember { mutableStateOf(false) }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2744)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⬇️ จัดการโมเดล AI", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                // Model status summary
                Text(
                    "📦 VLM (Gemma 4 E2B): ${if (modelManager.isVlmReady()) "✅ พร้อม (${modelManager.getVlmFileSizeMB()} MB)" else "❌ ยังไม่ได้โหลด"}",
                    color = if (modelManager.isVlmReady()) Color(0xFF10B981) else Color(0xFFEF4444),
                    fontSize = 13.sp
                )
                Text(
                    "🎙️ ASR Thai: ${if (modelManager.isAsrReady()) "✅ พร้อม (${modelManager.getAsrFileSizeMB()} MB)" else "❌ ยังไม่ได้ดาวน์โหลด"}",
                    color = if (modelManager.isAsrReady()) Color(0xFF10B981) else Color(0xFFEF4444),
                    fontSize = 13.sp
                )
                Text(
                    "💾 พื้นที่ว่าง: ${modelManager.getAvailableSpaceMB()} MB",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // VLM: auto download
                if (!modelManager.isVlmReady()) {
                    if (vlmDownloadProgress >= 0f && vlmDownloadProgress < 1f) {
                        LinearProgressIndicator(
                            progress = { vlmDownloadProgress },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            color = Color(0xFF10B981)
                        )
                        Text(vlmStatusText, color = Color.LightGray, fontSize = 11.sp)
                    } else {
                        Button(
                            onClick = {
                                if (!isDownloading) {
                                    if (!modelManager.hasEnoughSpace()) {
                                        vlmStatusText = "❌ พื้นที่ในเครื่องไม่พอสำหรับประมวลผล (ต้องใช้พื้นที่ว่าง > 2.5 GB)"
                                        return@Button
                                    }
                                    isDownloading = true
                                    coroutineScope.launch {
                                        val modelFile = ModelFile.VLM_MODEL
                                        val destFile = modelManager.getDestFile(modelFile)
                                        ModelDownloader.downloadFile(modelFile.url, destFile, modelFile.displayName)
                                            .collect { progress ->
                                                vlmDownloadProgress = progress.progressPercent
                                                vlmStatusText = "${progress.fileName}: ${"%.1f".format(progress.progressPercent * 100)}%"
                                                if (progress.error != null) {
                                                    vlmStatusText = "❌ ${progress.error}"
                                                }
                                            }
                                        isDownloading = false
                                        vlmDownloadProgress = -1f
                                        if (modelManager.isVlmReady()) {
                                            assistiveService?.reinitializePipelines()
                                        }
                                    }
                                }
                            },
                            enabled = !isDownloading,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("⬇️ ดาวน์โหลด VLM Gemma Model (~1.5 GB)", fontSize = 13.sp)
                        }
                        if (vlmStatusText.isNotEmpty()) {
                            Text(vlmStatusText, color = Color(0xFFEF4444), fontSize = 11.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ASR: auto download
                if (!modelManager.isAsrReady()) {
                    if (asrDownloadProgress >= 0f && asrDownloadProgress < 1f) {
                        LinearProgressIndicator(
                            progress = { asrDownloadProgress },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            color = Color(0xFF3B82F6)
                        )
                        Text(asrStatusText, color = Color.LightGray, fontSize = 11.sp)
                    } else {
                        Button(
                            onClick = {
                                if (!isDownloading) {
                                    isDownloading = true
                                    coroutineScope.launch {
                                        val files = listOf(
                                            ModelFile.ASR_ENCODER,
                                            ModelFile.ASR_DECODER,
                                            ModelFile.ASR_JOINER,
                                            ModelFile.ASR_TOKENS
                                        )
                                        for (modelFile in files) {
                                            val destFile = modelManager.getDestFile(modelFile)
                                            ModelDownloader.downloadFile(modelFile.url, destFile, modelFile.displayName)
                                                .collect { progress ->
                                                    asrDownloadProgress = progress.progressPercent
                                                    asrStatusText = "${progress.fileName}: ${"%.0f".format(progress.progressPercent * 100)}%"
                                                    if (progress.error != null) {
                                                        asrStatusText = "❌ ${progress.error}"
                                                    }
                                                }
                                        }
                                        isDownloading = false
                                        asrDownloadProgress = -1f
                                        if (modelManager.isAsrReady()) {
                                            assistiveService?.reinitializePipelines()
                                        }
                                    }
                                }
                            },
                            enabled = !isDownloading,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("⬇️ ดาวน์โหลด ASR Thai Model (~50 MB)", fontSize = 13.sp)
                        }
                        if (asrStatusText.isNotEmpty()) {
                            Text(asrStatusText, color = Color(0xFFEF4444), fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Reload button (after manual ADB push)
                Button(
                    onClick = { assistiveService?.reinitializePipelines() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔄 โหลดโมเดลใหม่ (หลัง ADB push)", fontSize = 13.sp)
                }
            }
        }
    }

    // ========================================
    // DEVELOPER PANEL
    // ========================================
    @kotlin.OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    @Composable
    fun DeveloperPanel() {
        val context = LocalContext.current
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        val logsText by AppLogger.logFlow.collectAsState()
        val arcoreLogsText by AppLogger.arcoreLogFlow.collectAsState()
        var selectedLogTab by remember { mutableStateOf(0) } // 0 = System, 1 = ARCore
        val activeLogText = if (selectedLogTab == 0) logsText else arcoreLogsText
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF334155)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "🛠 Developer Controls",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "Simulate Voice Commands:",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = { assistiveService?.handleVoiceCommand("อ่าน") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
                        modifier = Modifier.weight(1f)
                    ) { Text("อ่านข้อความ", fontSize = 10.sp) }
                    Button(
                        onClick = { assistiveService?.handleVoiceCommand("ดู") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                        modifier = Modifier.weight(1f)
                    ) { Text("ระบุสิ่งของ", fontSize = 10.sp) }
                    Button(
                        onClick = { assistiveService?.handleVoiceCommand("ข้างหน้า") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB45309)),
                        modifier = Modifier.weight(1f)
                    ) { Text("เช็คทางเดิน", fontSize = 10.sp) }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = { assistiveService?.handleVoiceCommand("มีอะไร") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                        modifier = Modifier.weight(1f)
                    ) { Text("สภาพแวดล้อม", fontSize = 10.sp) }
                    Button(
                        onClick = { assistiveService?.handleVoiceCommand("อันตราย") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF991B1B)),
                        modifier = Modifier.weight(1f)
                    ) { Text("ตรวจอันตราย", fontSize = 10.sp) }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Test Haptic Alerts (3 ระดับตาม Proposal):",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = { assistiveService?.hapticManager?.vibrateGeneralInfo() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "ทดสอบสั่น ระดับ 1 ข้อมูลทั่วไป" }
                    ) { Text("Lv1 ทั่วไป", fontSize = 10.sp) }
                    Button(
                        onClick = { assistiveService?.hapticManager?.vibrateWarning() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "ทดสอบสั่น ระดับ 2 เตือน" }
                    ) { Text("Lv2 เตือน", fontSize = 10.sp) }
                    Button(
                        onClick = { assistiveService?.hapticManager?.vibrateDanger() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "ทดสอบสั่น ระดับ 3 อันตราย" }
                    ) { Text("Lv3 อันตราย", fontSize = 10.sp) }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "⚙️ VLM Tuning Controls (ปรับแต่งโทเคนและความละเอียด)",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Max Generation Tokens
                val prefs = remember { context.getSharedPreferences("vlm_settings", Context.MODE_PRIVATE) }
                var maxTokens by remember { mutableStateOf(prefs.getInt("max_num_tokens", 512)) }
                var visualBudget by remember { mutableStateOf(prefs.getInt("visual_token_budget", -1)) }
                var resolution by remember { mutableStateOf(prefs.getInt("vlm_image_resolution", 224)) }

                Text(
                    text = "1. Max Output Tokens: $maxTokens (ต้องโหลดโมเดลใหม่จึงจะมีผล)",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(128, 256, 512, 1024).forEach { value ->
                        val isSelected = maxTokens == value
                        Button(
                            onClick = {
                                maxTokens = value
                                prefs.edit().putInt("max_num_tokens", value).apply()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFF10B981) else Color(0xFF475569)
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            modifier = Modifier.weight(1f).height(32.dp)
                        ) {
                            Text(value.toString(), fontSize = 10.sp, color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Visual Token Budget
                Text(
                    text = "2. Visual Token Budget: ${if (visualBudget == -1) "Dynamic" else visualBudget.toString()} (ปรับปรุงความฉลาด/การอ่านข้อความ)",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(-1, 140, 280, 560, 840).forEach { value ->
                        val isSelected = visualBudget == value
                        val label = if (value == -1) "Auto" else value.toString()
                        Button(
                            onClick = {
                                visualBudget = value
                                prefs.edit().putInt("visual_token_budget", value).apply()
                                // If engine is initialized, apply immediately
                                try {
                                    if (value != -1) {
                                        com.google.ai.edge.litertlm.ExperimentalFlags.visualTokenBudget = value
                                    }
                                } catch (e: Exception) {
                                    Log.w("MainActivity", "Failed to apply visual token budget immediately: ${e.message}")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFF10B981) else Color(0xFF475569)
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            modifier = Modifier.weight(1f).height(32.dp)
                        ) {
                            Text(label, fontSize = 9.sp, color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // VLM Image Resolution
                Text(
                    text = "3. Input Image Resolution: ${resolution}x${resolution} (มีผลทันที / ขนาดใหญ่ช่วยให้อ่านตัวหนังสือชัดขึ้น)",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(224, 320, 384, 448).forEach { value ->
                        val isSelected = resolution == value
                        Button(
                            onClick = {
                                resolution = value
                                prefs.edit().putInt("vlm_image_resolution", value).apply()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFF10B981) else Color(0xFF475569)
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            modifier = Modifier.weight(1f).height(32.dp)
                        ) {
                            Text("${value}px", fontSize = 10.sp, color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        TextButton(
                            onClick = { selectedLogTab = 0 },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (selectedLogTab == 0) Color(0xFF10B981) else Color.LightGray
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("📋 System", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = { selectedLogTab = 1 },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (selectedLogTab == 1) Color(0xFF3B82F6) else Color.LightGray
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("🧱 ARCore", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(activeLogText))
                                android.widget.Toast.makeText(context, "คัดลอกเรียบร้อยแล้ว", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF60A5FA)),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = { AppLogger.clearLogs() },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF87171)),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("Clear", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
 
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color.Black, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(activeLogText) {
                        scrollState.scrollTo(scrollState.maxValue)
                    }
                    Text(
                        text = activeLogText.ifEmpty { if (selectedLogTab == 0) "No system logs recorded." else "No ARCore logs recorded." },
                        color = if (selectedLogTab == 0) Color(0xFF4ADE80) else Color(0xFF60A5FA), // Green for System, Blue for ARCore
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    )
                }
            }
        }
    }
}

enum class ActiveMode(val label: String, val speech: String, val command: String) {
    READ("📖 อ่านหนังสือ (OCR)", "โหมดอ่านหนังสือ", "อ่าน"),
    OBJECT("👁️ หาวัตถุบนโต๊ะ", "โหมดหาวัตถุ", "ดู"),
    OBSTACLE("🚧 ตรวจสิ่งกีดขวาง", "โหมดตรวจจับสิ่งกีดขวาง", "ข้างหน้า");

    fun next(): ActiveMode = when (this) {
        READ -> OBJECT
        OBJECT -> OBSTACLE
        OBSTACLE -> READ
    }

    fun previous(): ActiveMode = when (this) {
        READ -> OBSTACLE
        OBJECT -> READ
        OBSTACLE -> OBJECT
    }
}

@Composable
fun PerformanceMetricRow(
    label: String,
    valueText: String,
    progress: Float,
    color: Color,
    contentDescription: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.LightGray, fontSize = 12.sp)
            Text(valueText, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = Color.White.copy(alpha = 0.1f)
        )
    }
}

// ─── AR Distance Overlay ─────────────────────────────────────────────────────

/**
 * Real-time distance overlay shown inside the camera viewport.
 *
 * Layout:
 *  ┌──────────────────────────────────────────────┐
 *  │  🔍 วัดระยะ Real-Time            [ARCore✓]  │
 *  │  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━   │
 *  │  🔴  คน                      0.6 เมตร       │
 *  │  🟡  เก้าอี้                  1.2 เมตร       │
 *  │  ⚪  โต๊ะ                     2.8 เมตร       │
 *  └──────────────────────────────────────────────┘
 *
 * Color coding:
 *   🔴 Red   — DANGER  (< 0.8m)
 *   🟠 Amber — WARNING (< 1.5m)
 *   🟡 Yellow— NEAR    (< 3.0m)
 *   ⚪ Gray  — CLEAR   (≥ 3.0m)
 */
@Composable
fun ArDistanceOverlay(
    results: List<DistanceResult>,
    modifier: Modifier = Modifier
) {
    val closest = results.firstOrNull()
    val headerColor = when {
        closest == null                              -> Color(0xFF10B981)
        closest.distanceMeters < 0.8f               -> Color(0xFFEF4444)
        closest.distanceMeters < 1.5f               -> Color(0xFFF59E0B)
        closest.distanceMeters < 3.0f               -> Color(0xFFEAB308)
        else                                         -> Color(0xFF10B981)
    }
    val depthLabel = if (closest?.isDepthReal == true) "ARCore ✓" else "ประมาณ"

    Column(
        modifier = modifier
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ── Header row ───────────────────────────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "🔍 วัดระยะ",
                color = headerColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = depthLabel,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
        }

        // ── Divider ──────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.2f))
        )

        // ── Closest object — big display ─────────────────────────────────────
        if (closest != null) {
            val bigColor = when {
                closest.distanceMeters < 0.8f  -> Color(0xFFEF4444)
                closest.distanceMeters < 1.5f  -> Color(0xFFF59E0B)
                closest.distanceMeters < 3.0f  -> Color(0xFFEAB308)
                else                            -> Color.White
            }
            val distStr = String.format("%.1f", closest.distanceMeters)
            val alertIcon = when {
                closest.distanceMeters < 0.8f  -> "🔴"
                closest.distanceMeters < 1.5f  -> "🟠"
                closest.distanceMeters < 3.0f  -> "🟡"
                else                            -> "⚪"
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = alertIcon, fontSize = 16.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = closest.labelThai,
                    color = bigColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$distStr ม.",
                    color = bigColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // ── Other objects (up to 4 more) ─────────────────────────────────────
        results.drop(1).take(4).forEach { result ->
            val distStr = String.format("%.1f", result.distanceMeters)
            val textColor = when {
                result.distanceMeters < 0.8f  -> Color(0xFFEF4444)
                result.distanceMeters < 1.5f  -> Color(0xFFF59E0B)
                result.distanceMeters < 3.0f  -> Color(0xFFEAB308)
                else                           -> Color.LightGray
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = result.labelThai,
                    color = textColor,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$distStr ม.",
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
