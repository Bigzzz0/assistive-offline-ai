package com.assistive.system.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.assistive.system.MainActivity
import com.assistive.system.ai.InferenceEngine
import com.assistive.system.audio.AudioPipeline
import com.assistive.system.haptic.HapticManager
import com.assistive.system.monitoring.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AssistiveService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Pipelines
    lateinit var audioPipeline: AudioPipeline
        private set
    lateinit var inferenceEngine: InferenceEngine
        private set
    lateinit var hapticManager: HapticManager
        private set
    lateinit var performanceMonitor: PerformanceMonitor
        private set

    // Service state
    private val _serviceStatus = MutableStateFlow("กำลังเริ่มต้นระบบ...")
    val serviceStatus: StateFlow<String> = _serviceStatus

    private val _inferenceOutput = MutableStateFlow("")
    val inferenceOutput: StateFlow<String> = _inferenceOutput

    data class AnalysisTask(
        val imageBytes: ByteArray,
        val prompt: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val pendingPromptsQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val analysisQueue = java.util.concurrent.ConcurrentLinkedQueue<AnalysisTask>()
    private val MAX_QUEUE_SIZE = 3
    private var isAnalyzing = false
 
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "AssistiveServiceChannel"

    inner class LocalBinder : Binder() {
        fun getService(): AssistiveService = this@AssistiveService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("AssistiveService", "Service onCreate")
        
        // 1. Start as foreground service immediately
        createNotificationChannel()
        startForegroundServiceCompat()

        // 2. Initialize pipelines
        hapticManager = HapticManager(this)
        performanceMonitor = PerformanceMonitor(this)
        audioPipeline = AudioPipeline(this)
        inferenceEngine = InferenceEngine(this)

        // Initialize AI engine asynchronously
        serviceScope.launch {
            _serviceStatus.value = "กำลังเตรียมแบบจำลองภาษา..."
            val success = inferenceEngine.initialize()
            val isVlmReal = !inferenceEngine.isMockMode()
            val isAsrReal = audioPipeline.isAsrReady()
            performanceMonitor.updateVlmMode(isVlmReal)
            performanceMonitor.updateAsrMode(isAsrReal)
            performanceMonitor.startBatteryTracking()

            val modeLabel = when {
                isVlmReal && isAsrReal -> "Real Mode ✅"
                isVlmReal -> "VLM จริง / ASR จำลอง"
                isAsrReal -> "VLM จำลอง / ASR จริง"
                else -> "⚠️ Mock Mode — ดาวน์โหลดโมเดลก่อนใช้งาน"
            }

            if (success) {
                _serviceStatus.value = "ระบบพร้อมทำงาน ($modeLabel)"
                hapticManager.vibrateGeneralInfo()
                audioPipeline.startListening { text ->
                    handleVoiceCommand(text)
                }
            } else {
                _serviceStatus.value = "ระบบขัดข้อง กรุณาตรวจสอบไฟล์โมเดล"
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("AssistiveService", "Service started command")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun hasPendingPrompt(): Boolean {
        return !pendingPromptsQueue.isEmpty()
    }

    private fun enqueuePrompt(promptStr: String) {
        if (pendingPromptsQueue.size >= MAX_QUEUE_SIZE) {
            pendingPromptsQueue.poll()
        }
        pendingPromptsQueue.offer(promptStr)
    }

    fun handleVoiceCommand(text: String) {
        val prompt = text.lowercase()
        Log.i("AssistiveService", "Voice Command received: $prompt")
        
        when {
            prompt.contains("หยุด") -> {
                pendingPromptsQueue.clear()
                analysisQueue.clear()
                audioPipeline.stopSpeaking()
                isAnalyzing = false
                audioPipeline.speak("หยุดทำงาน")
                _serviceStatus.value = "หยุดทำงานชั่วคราว"
            }
            prompt.contains("อ่าน") -> {
                enqueuePrompt("อ่านป้ายและข้อความภาษาไทยในภาพ")
                _serviceStatus.value = "คำสั่ง: กำลังอ่านข้อความ..."
                hapticManager.vibrateGeneralInfo()
                audioPipeline.speak("กำลังอ่านข้อความ")
            }
            prompt.contains("ดู") || prompt.contains("สิ่งของ") -> {
                enqueuePrompt("ระบุสิ่งของที่วางอยู่บนโต๊ะด้านหน้า")
                _serviceStatus.value = "คำสั่ง: กำลังบอกสิ่งของ..."
                hapticManager.vibrateGeneralInfo()
                audioPipeline.speak("กำลังสแกนสิ่งของ")
            }
            prompt.contains("ข้างหน้า") || prompt.contains("กีดขวาง") -> {
                enqueuePrompt("ตรวจสอบสิ่งกีดขวางบนทางเดินด้านหน้าและเตือนภัย")
                _serviceStatus.value = "คำสั่ง: ตรวจสอบสิ่งกีดขวาง..."
                hapticManager.vibrateWarning()
                audioPipeline.speak("กำลังตรวจสอบสิ่งกีดขวาง")
            }
            prompt.contains("มีอะไร") || prompt.contains("ช่วย") -> {
                enqueuePrompt("อธิบายสภาพแวดล้อมรอบตัวในภาพโดยละเอียด")
                _serviceStatus.value = "คำสั่ง: กำลังวิเคราะห์สภาพแวดล้อม..."
                hapticManager.vibrateGeneralInfo()
                audioPipeline.speak("กำลังวิเคราะห์สภาพแวดล้อม")
            }
            prompt.contains("อันตราย") -> {
                enqueuePrompt("มีสิ่งอันตรายในภาพหรือไม่")
                _serviceStatus.value = "คำสั่ง: กำลังตรวจสอบความปลอดภัย..."
                hapticManager.vibrateWarning()
                audioPipeline.speak("กำลังตรวจสอบความปลอดภัย")
            }
        }
    }

    /**
     * Entry point for camera frames coming from VisionPipeline.
     */
    fun onCameraFrameAvailable(jpegBytes: ByteArray) {
        val prompt = pendingPromptsQueue.poll() ?: return
        
        if (analysisQueue.size >= MAX_QUEUE_SIZE) {
            val evicted = analysisQueue.poll()
            Log.w("AssistiveService", "Queue full, evicting task with prompt: ${evicted?.prompt}")
        }
        
        analysisQueue.offer(AnalysisTask(jpegBytes, prompt))
        serviceScope.launch {
            processNextTask()
        }
    }

    private fun processNextTask() {
        if (isAnalyzing) return
        val task = analysisQueue.poll() ?: return
        isAnalyzing = true

        _serviceStatus.value = "กำลังประมวลผลด้วย AI..."
        _inferenceOutput.value = ""

        serviceScope.launch {
            val fullResponse = StringBuilder()
            val startTime = System.currentTimeMillis()

            inferenceEngine.analyzeImageStream(task.imageBytes, task.prompt).collect { token ->
                fullResponse.append(token)
                _inferenceOutput.value = fullResponse.toString()
            }

            val latencyMs = System.currentTimeMillis() - startTime
            val tokenCount = fullResponse.split(" ").size
            performanceMonitor.recordInference(latencyMs, tokenCount)
            performanceMonitor.refreshMemoryUsage()
            performanceMonitor.refreshTemperature()
            performanceMonitor.refreshBatteryDrain()

            val finalOutput = fullResponse.toString()
            val validatedOutput = if (task.prompt.contains("อ่าน")) {
                com.assistive.system.ai.OcrPostValidator.validateOcrResult(finalOutput)
            } else {
                finalOutput
            }

            _inferenceOutput.value = validatedOutput
            _serviceStatus.value = "ระบบพร้อมทำงาน (${latencyMs}ms)"

            // Trigger Haptic Feedback based on the VLM output severity
            triggerResponseHaptics(validatedOutput)

            // Speak the VLM output
            audioPipeline.speak(validatedOutput) {
                serviceScope.launch {
                    isAnalyzing = false
                    processNextTask()
                }
            }
        }
    }

    private fun triggerResponseHaptics(text: String) {
        when {
            text.contains("อันตราย") || text.contains("กีดขวาง") || text.contains("ขวางทาง") -> {
                hapticManager.vibrateDanger()
            }
            text.contains("ระมัดระวัง") || text.contains("เตือน") || text.contains("ไม่แน่ใจ") -> {
                hapticManager.vibrateWarning()
            }
            else -> {
                hapticManager.vibrateGeneralInfo()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Assistive Offline AI Service"
            val descriptionText = "Handles Offline AI Vision and Speech recognition."
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceCompat() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Assistive Offline AI running")
            .setContentText("ระบบช่วยเหลือผู้บกพร่องทางการมองเห็นกำลังทำงาน")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * โหลดโมเดลใหม่ทั้งหมดหลังดาวน์โหลดสำเร็จ (ไม่ต้อง restart แอป)
     */
    fun reinitializePipelines() {
        serviceScope.launch {
            _serviceStatus.value = "กำลังโหลดโมเดลที่ดาวน์โหลดมา..."
            inferenceEngine.reinitialize()
            audioPipeline.reinitializeRecognizer()
            val isVlmReal = !inferenceEngine.isMockMode()
            val isAsrReal = audioPipeline.isAsrReady()
            performanceMonitor.updateVlmMode(isVlmReal)
            performanceMonitor.updateAsrMode(isAsrReal)
            val modeLabel = if (isVlmReal && isAsrReal) "Real Mode ✅" else "Partial Mode"
            _serviceStatus.value = "โหลดสำเร็จ ($modeLabel)"
            hapticManager.vibrateGeneralInfo()
            audioPipeline.speak("โหลดโมเดลสำเร็จ ระบบพร้อมทำงาน")
        }
    }

    override fun onDestroy() {
        Log.i("AssistiveService", "Service onDestroy")
        serviceScope.cancel()
        audioPipeline.release()
        inferenceEngine.release()
        super.onDestroy()
    }
}
