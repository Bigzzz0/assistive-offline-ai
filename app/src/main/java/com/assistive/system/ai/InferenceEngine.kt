package com.assistive.system.ai

import android.content.Context
import android.graphics.Bitmap
import com.assistive.system.logging.AppLogger as Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

class InferenceEngine(
    private val context: Context,
    private val modelPath: String = run {
        // Prioritize HuggingFace download name, fall back to legacy name
        val primary = File(context.filesDir, "gemma-4-E2B-it.litertlm")
        val legacy  = File(context.filesDir, "gemma_vlm.litertlm")
        when {
            primary.exists() -> primary.absolutePath
            legacy.exists()  -> legacy.absolutePath
            else             -> primary.absolutePath // default path for error messages
        }
    },
    private val loraAdapterPath: String? = run {
        val loraDir = File(context.filesDir, "lora_adapter")
        if (loraDir.exists()) loraDir.absolutePath else null
    }
) {

    private var engine: Engine? = null
    private var isInitialized = false
    private var isMockMode = false

    // System Prompt to enforce constraint decoding format (Thai BLV Assistant)
    private val systemPrompt = """
        บทบาท: คุณคือผู้ช่วยคนตาบอดภาษาไทย ตอบข้อมูลสั้นและตรงประเด็นที่สุด
        รูปแบบคำตอบที่บังคับ: [ชื่อวัตถุ] - [ตำแหน่งโดยประมาณ] (ความมั่นใจ: [ต่ำ/ปานกลาง/สูง])
        ตัวอย่าง: แก้วน้ำ - บนโต๊ะด้านหน้าขวา (ความมั่นใจ: สูง)
        ห้ามอธิบายยาว ห้ามใช้คำฟุ่มเฟือย ถ้าไม่มั่นใจอย่างมาก ให้ตอบว่า (ความมั่นใจ: ต่ำ)
        ระบบนี้เป็นเครื่องมือช่วยเหลือเสริม ไม่ได้ออกแบบมาเพื่อการนำทางโดยตรง
    """.trimIndent()

    init {
        checkModelAndSetup()
        if (!isMockMode) {
            warmOSPageCache(modelPath)
        }
    }

    // ===== Public API =====

    /** ตรวจสอบว่าอยู่ในโหมดจำลองหรือไม่ */
    fun isMockMode(): Boolean = isMockMode

    /** โหลดโมเดลใหม่หลังดาวน์โหลดสำเร็จ */
    suspend fun reinitialize() {
        withContext(Dispatchers.IO) {
            release()
            checkModelAndSetup()
            initialize()
            Log.i("InferenceEngine", "Reinitialized. MockMode=$isMockMode")
        }
    }

    // ===== Internal Setup =====

    private fun checkModelAndSetup() {
        val file = File(modelPath)
        if (!file.exists() || file.length() < 100_000_000L) {
            Log.w("InferenceEngine", "VLM model not found at $modelPath. Running in Mock Mode.")
            isMockMode = true
            isInitialized = true
            return
        }
        isMockMode = false
    }

    @OptIn(ExperimentalApi::class)
    suspend fun initialize(): Boolean {
        if (isMockMode) return true
        return withContext(Dispatchers.IO) {
            if (loraAdapterPath != null) {
                Log.i("InferenceEngine", "LoRA adapter detected at $loraAdapterPath")
            }

            applyDynamicVisualTokenBudget()

            val isEmulator = android.os.Build.HARDWARE.contains("goldfish")
                    || android.os.Build.HARDWARE.contains("ranchu")
                    || android.os.Build.PRODUCT.contains("sdk")
                    || android.os.Build.MODEL.contains("Emulator")
                    || android.os.Build.MODEL.contains("google_sdk")

            val persistentCacheDir = File(context.filesDir, "litert_shader_cache").apply { mkdirs() }.absolutePath

            if (!isEmulator) {
                // 1. Try GPU first (recommended for most real devices running VLM)
                try {
                    Log.i("InferenceEngine", "Attempting LiteRT-LM Engine initialization on GPU...")
                    val config = EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.GPU(),
                        visionBackend = Backend.CPU(),
                        maxNumTokens = 512,
                        cacheDir = persistentCacheDir
                    )
                    val gpuEngine = Engine(config)
                    gpuEngine.initialize()
                    engine = gpuEngine
                    isInitialized = true
                    isMockMode = false
                    Log.i("InferenceEngine", "LiteRT-LM Engine initialized successfully with GPU acceleration.")
                    return@withContext true
                } catch (e: Exception) {
                    Log.e("InferenceEngine", "GPU initialization failed: ${e.message}. Falling back to NPU.", e)
                }

                // 2. Try NPU second (uses hardware acceleration on chips supporting it)
                try {
                    Log.i("InferenceEngine", "Attempting LiteRT-LM Engine initialization on NPU...")
                    val config = EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir),
                        visionBackend = Backend.CPU(),
                        maxNumTokens = 512,
                        cacheDir = persistentCacheDir
                    )
                    val npuEngine = Engine(config)
                    npuEngine.initialize()
                    engine = npuEngine
                    isInitialized = true
                    isMockMode = false
                    Log.i("InferenceEngine", "LiteRT-LM Engine initialized successfully with NPU acceleration.")
                    return@withContext true
                } catch (e: Exception) {
                    Log.e("InferenceEngine", "NPU initialization failed: ${e.message}. Falling back to CPU.", e)
                }
            } else {
                Log.i("InferenceEngine", "Running on Emulator. Bypassing GPU/NPU initialization to accelerate startup.")
            }

            // 3. Fallback to CPU
            try {
                Log.i("InferenceEngine", "Attempting LiteRT-LM Engine initialization on CPU...")
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    maxNumTokens = 512,
                    cacheDir = persistentCacheDir
                )
                val cpuEngine = Engine(config)
                cpuEngine.initialize()
                engine = cpuEngine
                isInitialized = true
                isMockMode = false
                Log.i("InferenceEngine", "LiteRT-LM Engine initialized successfully with CPU fallback.")
                return@withContext true
            } catch (e: Exception) {
                Log.e("InferenceEngine", "CPU initialization failed: ${e.message}. Running Mock Mode.", e)
                isMockMode = true
                isInitialized = true
                return@withContext true
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun applyDynamicVisualTokenBudget() {
        try {
            val budget = getAppropriateTokenBudget()
            com.google.ai.edge.litertlm.ExperimentalFlags.visualTokenBudget = budget
            Log.i("InferenceEngine", "Set visualTokenBudget dynamically to $budget")
        } catch (e: Exception) {
            Log.w("InferenceEngine", "Failed to set dynamic visualTokenBudget: ${e.message}")
        }
    }

    private fun getAppropriateTokenBudget(): Int {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val thermalStatus = powerManager?.currentThermalStatus ?: 0
            return when (thermalStatus) {
                0 -> 840  // Normal/Nominal
                1 -> 560  // Light
                2 -> 420  // Moderate
                3 -> 280  // Severe
                else -> 140 // Critical/Emergency/Shutdown
            }
        }
        
        // Fallback: Check battery temperature on older API levels
        val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val temp = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = temp / 10
        return when {
            tempCelsius < 38 -> 560
            tempCelsius < 43 -> 280
            else -> 140
        }
    }

    private fun warmOSPageCache(path: String) {
        thread(priority = Thread.MIN_PRIORITY) {
            try {
                val file = File(path)
                if (file.exists()) {
                    val buffer = ByteArray(64 * 1024)
                    file.inputStream().use { input ->
                        while (input.read(buffer) != -1) {
                            // Read sequence to pull into OS Page Cache
                        }
                    }
                    Log.i("InferenceEngine", "OS Page Cache warming completed for $path")
                }
            } catch (e: Exception) {
                Log.w("InferenceEngine", "Failed to warm OS Page Cache: ${e.message}")
            }
        }
    }

    /**
     * วิเคราะห์ภาพและส่ง token stream กลับมา
     * @return Pair<Flow<String>, latencyMs callback>
     */
    fun analyzeImageStream(imageBytes: ByteArray, promptText: String): Flow<String> = flow {
        if (!isInitialized) {
            emit("ระบบยังไม่พร้อมใช้งาน")
            return@flow
        }

        // ======== MOCK MODE ========
        if (isMockMode) {
            kotlinx.coroutines.delay(1200)
            val mockResponse = when {
                promptText.contains("อ่าน") -> "ป้ายบอกทาง - ทางหนีไฟสีเขียวประตูด้านขวา (ความมั่นใจ: สูง)"
                promptText.contains("สิ่งของ") || promptText.contains("บนโต๊ะ") -> "แก้วน้ำและกุญแจ - อยู่ตรงกลางโต๊ะทำงาน (ความมั่นใจ: สูง)"
                promptText.contains("ข้างหน้า") || promptText.contains("กีดขวาง") -> "เก้าอี้ไม้ขวางทาง - อยู่ด้านหน้าประมาณ 1 เมตร (ความมั่นใจ: สูง)"
                else -> "สมาร์ทโฟน - ถืออยู่ในมือ (ความมั่นใจ: สูง)"
            }
            val tokens = mockResponse.split(" ")
            for (token in tokens) {
                emit("$token ")
                kotlinx.coroutines.delay(100)
            }
            return@flow
        }

        // ======== REAL MODE ========
        // Reuse the already initialized engine to avoid loading the model from disk on every request

        val currentEngine = engine ?: run {
            emit("เกิดข้อผิดพลาด: โมเดลไม่พร้อมใช้งาน")
            return@flow
        }

        try {
            currentEngine.createConversation().use { conversation ->
                val fullPrompt = "$systemPrompt\n\nคำสั่ง: $promptText"
                val contents = Contents.of(listOf(
                    Content.ImageBytes(imageBytes),
                    Content.Text(fullPrompt)
                ))

                val responseBuilder = StringBuilder()
                var isLowConfidence = false

                conversation.sendMessageAsync(contents).collect { token ->
                    val tokenStr = token.toString()
                    responseBuilder.append(tokenStr)
                    
                    val length = responseBuilder.length
                    if (length >= 12) {
                        val startIndex = (length - 30).coerceAtLeast(0)
                        val lastChunk = responseBuilder.substring(startIndex)
                        if (lastChunk.contains("ความมั่นใจ: ต่ำ") || lastChunk.contains("ความมั่นใจ:ต่ำ")) {
                            isLowConfidence = true
                        }
                    }
                    
                    if (!isLowConfidence) {
                        emit(tokenStr)
                    }
                }

                if (isLowConfidence) {
                    emit("\n[!] ระบบไม่แน่ใจในภาพที่เห็น กรุณาปรับมุมกล้องและลองใหม่อีกครั้ง")
                }
            }
        } catch (e: Exception) {
            Log.e("InferenceEngine", "Inference error: ${e.message}", e)
            emit("เกิดข้อผิดพลาดในการประมวลผลโมเดล")
        }
    }.flowOn(Dispatchers.IO)

    fun release() {
        if (!isMockMode) {
            engine?.close()
            engine = null
        }
        isInitialized = false
    }
}
