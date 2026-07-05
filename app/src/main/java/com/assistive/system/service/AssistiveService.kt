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
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.assistive.system.logging.AppLogger as Log
import androidx.core.app.NotificationCompat
import com.assistive.system.MainActivity
import com.assistive.system.ai.InferenceEngine
import com.assistive.system.audio.AudioPipeline
import com.assistive.system.haptic.HapticManager
import com.assistive.system.monitoring.PerformanceMonitor
import com.assistive.system.vision.AlertLevel
import com.assistive.system.vision.DistancePipeline
import com.assistive.system.vision.DistanceResult
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

    private val _currentlyAnalyzingBitmap = MutableStateFlow<Bitmap?>(null)
    val currentlyAnalyzingBitmap: StateFlow<Bitmap?> = _currentlyAnalyzingBitmap

    data class AnalysisTask(
        val imageBytes: ByteArray,
        val prompt: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val pendingPromptsQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val analysisQueue = java.util.concurrent.ConcurrentLinkedQueue<AnalysisTask>()
    private val MAX_QUEUE_SIZE = 1
    private var isAnalyzing = false
    private var activeAnalysisJob: kotlinx.coroutines.Job? = null

    // ─── Distance / AR subsystem ─────────────────────────────────────────────
    /** Publicly exposed so MainActivity can read and render AR overlay */
    private val _distanceResults = MutableStateFlow<List<DistanceResult>>(emptyList())
    val distanceResults: StateFlow<List<DistanceResult>> = _distanceResults

    /** External DistancePipeline injected by MainActivity after ARCore init */
    var distancePipeline: DistancePipeline? = null
        private set

    private var lastAlertTimeMs = 0L
    private val ALERT_COOLDOWN_MS = 1500L  // Minimum gap between TTS distance alerts
    private var lastDangerAlertMs = 0L
    private val DANGER_COOLDOWN_MS = 800L
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "AssistiveServiceChannel"

    inner class LocalBinder : Binder() {
        fun getService(): AssistiveService = this@AssistiveService
    }

    override fun onCreate() {
        super.onCreate()
        com.assistive.system.logging.AppLogger.init(applicationContext)
        Log.i("AssistiveService", "Service onCreate")
        
        // 1. Start as foreground service immediately
        createNotificationChannel()
        startForegroundServiceCompat()

        // 2. Initialize pipelines
        hapticManager = HapticManager(this)
        performanceMonitor = PerformanceMonitor(this)
        audioPipeline = AudioPipeline(this)
        inferenceEngine = InferenceEngine(this)

        // Initialize AI engine asynchronously in parallel
        serviceScope.launch {
            _serviceStatus.value = "กำลังเตรียมแบบจำลองภาษาและเสียง..."
            var success = false
            val vlmInitJob = launch(Dispatchers.IO) {
                success = inferenceEngine.initialize()
            }
            val asrInitJob = launch(Dispatchers.IO) {
                audioPipeline.initializeRecognizer()
            }
            vlmInitJob.join()
            asrInitJob.join()

            val isVlmReal = !inferenceEngine.isMockMode()
            val isAsrReal = audioPipeline.isAsrReady()
            performanceMonitor.updateVlmMode(isVlmReal, inferenceEngine.getActiveBackend())
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
        return !isAnalyzing && !pendingPromptsQueue.isEmpty()
    }

    /**
     * Called by MainActivity after ARCore + ObjectDetector initialization is complete.
     * Wires the DistancePipeline into the service and starts real-time processing.
     */
    fun attachDistancePipeline(pipeline: DistancePipeline) {
        distancePipeline = pipeline
        pipeline.start()
        Log.i("AssistiveService", "DistancePipeline attached and started")
    }

    /**
     * Callback from DistancePipeline — receives real-time distance results and
     * triggers appropriate haptic + TTS alerts based on proximity thresholds.
     *
     * Alert levels:
     *  DANGER  (< 0.8m)  → immediate danger vibration + TTS, 800ms cooldown
     *  WARNING (< 1.5m)  → warning vibration + TTS, 1500ms cooldown
     *  NEAR    (< 3.0m)  → gentle haptic only
     */
    fun onDistanceUpdate(results: List<DistanceResult>) {
        _distanceResults.value = results
        if (results.isEmpty()) return

        val now = System.currentTimeMillis()
        val closest = results.first() // already sorted by distance ascending

        when (distancePipeline?.getAlertLevel(closest.distanceMeters)) {
            AlertLevel.DANGER -> {
                if (now - lastDangerAlertMs >= DANGER_COOLDOWN_MS) {
                    lastDangerAlertMs = now
                    lastAlertTimeMs = now
                    hapticManager.vibrateDanger()
                    val distStr = String.format("%.1f", closest.distanceMeters)
                    audioPipeline.speak("${closest.labelThai} ระยะ $distStr เมตร")
                    Log.w("AssistiveService", "DANGER: ${closest.labelThai} at ${closest.distanceMeters}m")
                }
            }
            AlertLevel.WARNING -> {
                if (now - lastAlertTimeMs >= ALERT_COOLDOWN_MS) {
                    lastAlertTimeMs = now
                    hapticManager.vibrateWarning()
                    val distStr = String.format("%.1f", closest.distanceMeters)
                    audioPipeline.speak("${closest.labelThai} ${distStr} เมตร")
                    Log.i("AssistiveService", "WARNING: ${closest.labelThai} at ${closest.distanceMeters}m")
                }
            }
            AlertLevel.NEAR -> {
                if (now - lastAlertTimeMs >= ALERT_COOLDOWN_MS * 2) {
                    lastAlertTimeMs = now
                    hapticManager.vibrateGeneralInfo()
                }
            }
            else -> { /* CLEAR — no alert needed */ }
        }
    }

    private fun enqueuePrompt(promptStr: String) {
        if (pendingPromptsQueue.size >= MAX_QUEUE_SIZE) {
            pendingPromptsQueue.clear()
        }
        pendingPromptsQueue.offer(promptStr)
    }

    fun handleVoiceCommand(text: String) {
        val prompt = text.lowercase()
        Log.i("AssistiveService", "Voice Command received: $prompt")
        
        if (prompt.contains("หยุด")) {
            cancelCurrentAnalysis()
            return
        }

        if (!inferenceEngine.isInitialized()) {
            Log.w("AssistiveService", "Ignoring command because InferenceEngine is not initialized: $prompt")
            audioPipeline.speak("ระบบยังไม่พร้อมใช้งาน โมเดลกำลังโหลด")
            hapticManager.vibrateWarning()
            return
        }

        when {
            prompt.contains("วัดระยะ") || prompt.contains("ตรงกลาง") || prompt.contains("ระยะ") -> {
                _serviceStatus.value = "คำสั่ง: วัดระยะกึ่งกลาง..."
                hapticManager.vibrateGeneralInfo()
                val centerDepth = distancePipeline?.depthEstimator?.getDepthAtNormalizedPoint(0.5f, 0.5f) ?: -1f
                if (centerDepth > 0f) {
                    val closestObstacle = distancePipeline?.lastDistanceResults?.firstOrNull { it.boundingBox.contains(0.5f, 0.5f) }
                    val label = closestObstacle?.labelThai ?: "วัตถุตรงกลาง"
                    val distStr = String.format(java.util.Locale.US, "%.1f", centerDepth)
                    audioPipeline.speak("$label อยู่ห่างออกไป $distStr เมตร")
                } else {
                    val errMsg = distancePipeline?.depthEstimator?.arCoreErrorMessage ?: "ระบบไม่สามารถวัดระยะได้ในขณะนี้"
                    audioPipeline.speak("ไม่สามารถวัดระยะตรงกลางได้ เนื่องจาก $errMsg")
                }
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

        try {
            val bitmap = BitmapFactory.decodeByteArray(task.imageBytes, 0, task.imageBytes.size)
            _currentlyAnalyzingBitmap.value = bitmap
        } catch (e: Exception) {
            Log.e("AssistiveService", "Failed to decode bitmap: ${e.message}")
        }

        _serviceStatus.value = "กำลังประมวลผลด้วย AI..."
        _inferenceOutput.value = ""

        activeAnalysisJob = serviceScope.launch {
            try {
                val fullResponse = StringBuilder()
                val startTime = System.currentTimeMillis()

                // Pause microphone listening thread during VLM inference to avoid CPU/thread contention
                audioPipeline.pauseListening()

                try {
                    // Impose 45 seconds timeout to prevent infinite loading freezes on stalled inferences
                    kotlinx.coroutines.withTimeout(45000L) {
                        inferenceEngine.analyzeImageStream(task.imageBytes, task.prompt).collect { token ->
                            fullResponse.append(token)
                            _inferenceOutput.value = fullResponse.toString()
                        }
                    }
                } finally {
                    // Resume ASR listening thread
                    audioPipeline.resumeListening()
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

                // Hide the visual loading overlay immediately as the result is ready
                _currentlyAnalyzingBitmap.value = null

                // Trigger Haptic Feedback based on the VLM output severity
                triggerResponseHaptics(validatedOutput)

                // Speak the VLM output safely with a timeout so the engine never locks permanently
                try {
                    val speakTimeout = (validatedOutput.length * 150L).coerceIn(5000L, 25000L)
                    kotlinx.coroutines.withTimeoutOrNull(speakTimeout) {
                        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                            var resumed = false
                            audioPipeline.speak(validatedOutput) {
                                if (!resumed) {
                                    resumed = true
                                    if (cont.isActive) {
                                        cont.resumeWith(Result.success(Unit))
                                    }
                                }
                            }
                        }
                    }
                } catch (speakEx: Exception) {
                    Log.w("AssistiveService", "Timeout or issue during TTS speech: ${speakEx.message}")
                }

            } catch (e: Exception) {
                Log.e("AssistiveService", "Exception during analysis coroutine: ${e.message}", e)
                _serviceStatus.value = "เกิดข้อผิดพลาดในการประมวลผล"
                _currentlyAnalyzingBitmap.value = null
                audioPipeline.speak("เกิดข้อผิดพลาดในการประมวลผล")
            } finally {
                // GUARANTEED to unlock the engine under all circumstances
                activeAnalysisJob = null
                isAnalyzing = false
                _currentlyAnalyzingBitmap.value = null
                processNextTask()
            }
        }
    }

    fun cancelCurrentAnalysis() {
        Log.i("AssistiveService", "cancelCurrentAnalysis: Cancelling active analysis job...")
        activeAnalysisJob?.cancel()
        activeAnalysisJob = null
        isAnalyzing = false
        _currentlyAnalyzingBitmap.value = null
        pendingPromptsQueue.clear()
        analysisQueue.clear()
        audioPipeline.stopSpeaking()
        _serviceStatus.value = "ยกเลิกการประมวลผลแล้ว"
        audioPipeline.speak("ยกเลิกการทำงาน")
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
            performanceMonitor.updateVlmMode(isVlmReal, inferenceEngine.getActiveBackend())
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
        distancePipeline?.release()
        audioPipeline.release()
        inferenceEngine.release()
        super.onDestroy()
    }
}
