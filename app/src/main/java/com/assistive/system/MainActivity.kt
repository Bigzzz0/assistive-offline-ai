package com.assistive.system

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.assistive.system.service.AssistiveService
import com.assistive.system.vision.VisionPipeline
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private var assistiveService: AssistiveService? = null
    private var isBound = false
    private var visionPipeline: VisionPipeline? = null
    private lateinit var cameraExecutor: ExecutorService

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AssistiveService.LocalBinder
            assistiveService = binder.getService()
            isBound = true
            Log.i("MainActivity", "Connected to AssistiveService.")
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
        cameraExecutor = Executors.newSingleThreadExecutor()

        checkPermissionsAndStart()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF10B981), // Emerald
                    secondary = Color(0xFF3B82F6), // Blue
                    background = Color(0xFF0F172A), // Slate 900
                    surface = Color(0xFF1E293B) // Slate 800
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
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val audioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)

        if (cameraPermission == PackageManager.PERMISSION_GRANTED && audioPermission == PackageManager.PERMISSION_GRANTED) {
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

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        visionPipeline?.shutdown()
        cameraExecutor.shutdown()
    }

    @Composable
    fun AssistiveMainScreen() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        // Bind states from Service
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

        var showDevPanel by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = "ASSISTIVE OFFLINE AI",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            )
            // Camera Viewport / Layout
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        
                        // Setup CameraX once Service is ready and VisionPipeline isn't initialized yet
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            
                            // Define Vision Pipeline
                            visionPipeline = VisionPipeline(
                                context = ctx,
                                lifecycleOwner = lifecycleOwner,
                                isFrameRequested = { assistiveService?.hasPendingPrompt() == true }
                            ) { bitmap ->
                                assistiveService?.onCameraFrameAvailable(bitmap)
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            imageAnalysis.setAnalyzer(cameraExecutor, visionPipeline!!.getAnalyzer())

                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
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

            Spacer(modifier = Modifier.height(16.dp))

            // Main Status Display
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "สถานะการเชื่อมต่อ: $statusText" }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "สถานะระบบ",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = statusText,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Large Output Display for Visually Impaired Voice Feedback representation
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .clickable {
                        // Repeating the reading out when clicking on the output card
                        if (aiResultText.isNotEmpty()) {
                            assistiveService?.audioPipeline?.speak(aiResultText)
                        }
                    }
                    .semantics { contentDescription = "คำอธิบายภาพ: ${if (aiResultText.isEmpty()) "ไม่มีข้อมูล" else aiResultText}" }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (aiResultText.isEmpty()) "ระบบจะอธิบายภาพสภาพแวดล้อมตรงหน้าผ่านเสียงพูดและการสั่นเมื่อผู้ใช้สั่งการ" else aiResultText,
                        color = if (aiResultText.isEmpty()) Color.Gray else Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Dev Panel toggle button
            Button(
                onClick = { showDevPanel = !showDevPanel },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (showDevPanel) "ซ่อนแผงผู้พัฒนา (Hide Developer Panel)" else "แสดงแผงผู้พัฒนา (Show Developer Panel)")
            }

            if (showDevPanel) {
                Spacer(modifier = Modifier.height(16.dp))
                DeveloperPanel(context)
            }
        }
    }

    @Composable
    fun DeveloperPanel(context: Context) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF334155)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Developer Controls & Stats",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Mock memory stat
                val runtime = Runtime.getRuntime()
                val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                Text(
                    text = "JVM Memory usage: $usedMemory MB / ${runtime.maxMemory() / (1024 * 1024)} MB",
                    color = Color.Yellow,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Simulate Voice Command Inputs directly via Buttons
                Text(
                    text = "Simulate Voice Commands (คำสั่งปุ่มสัมผัส):",
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = { assistiveService?.handleVoiceCommand("อ่าน") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                    ) {
                        Text("อ่านข้อความ", fontSize = 11.sp)
                    }
                    Button(
                        onClick = { assistiveService?.handleVoiceCommand("ดู") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                    ) {
                        Text("ระบุสิ่งของ", fontSize = 11.sp)
                    }
                    Button(
                        onClick = { assistiveService?.handleVoiceCommand("ข้างหน้า") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    ) {
                        Text("เช็คทางเดิน", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Manually trigger haptic testing
                Text(
                    text = "Test Haptic Alerts (ทดสอบระดับสั่น):",
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = { assistiveService?.hapticManager?.vibrateGeneralInfo() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                    ) {
                        Text("Level 1 (ทั่วไป)", fontSize = 10.sp)
                    }
                    Button(
                        onClick = { assistiveService?.hapticManager?.vibrateWarning() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                    ) {
                        Text("Level 2 (เตือน)", fontSize = 10.sp)
                    }
                    Button(
                        onClick = { assistiveService?.hapticManager?.vibrateDanger() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    ) {
                        Text("Level 3 (อันตราย)", fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
