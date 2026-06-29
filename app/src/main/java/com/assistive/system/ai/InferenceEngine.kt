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
        val path = file.absolutePath
        val exists = file.exists()
        val size = if (exists) file.length() else 0L
        val sizeMB = size / (1024 * 1024)
        val canRead = if (exists) file.canRead() else false

        Log.i("InferenceEngine", "--- VLM Model Diagnostics ---")
        Log.i("InferenceEngine", "Model Path: $path")
        Log.i("InferenceEngine", "Exists: $exists")
        Log.i("InferenceEngine", "Size: $size bytes ($sizeMB MB)")
        Log.i("InferenceEngine", "Can Read: $canRead")
        Log.i("InferenceEngine", "-----------------------------")

        val isEmulator = android.os.Build.HARDWARE.contains("goldfish")
                || android.os.Build.HARDWARE.contains("ranchu")
                || android.os.Build.PRODUCT.contains("sdk")
                || android.os.Build.MODEL.contains("Emulator")
                || android.os.Build.MODEL.contains("google_sdk")

        val isX86 = android.os.Build.SUPPORTED_ABIS.any { it.contains("x86") }

        if (isEmulator && isX86) {
            Log.w("InferenceEngine", "Detected x86/x86_64 Emulator. Multimodal LiteRT-LM (VLM) vision models are unsupported on x86 emulator architectures and will crash during JNI execution. Forcing Mock Mode for stability. Please use a physical ARM64 Android device to run the real model.")
            isMockMode = true
            isInitialized = true
            return
        }

        if (!exists) {
            Log.w("InferenceEngine", "VLM model file does NOT exist at $path. Running in Mock Mode.")
            isMockMode = true
            isInitialized = true
            return
        }
        
        if (size < 100_000_000L) {
            Log.w("InferenceEngine", "VLM model file at $path is too small ($sizeMB MB < 100 MB). It might be corrupted or incomplete. Running in Mock Mode.")
            isMockMode = true
            isInitialized = true
            return
        }
        
        if (!canRead) {
            Log.w("InferenceEngine", "VLM model file at $path exists but cannot be read due to permission issues. Running in Mock Mode.")
            isMockMode = true
            isInitialized = true
            return
        }

        isMockMode = false
    }

    @OptIn(ExperimentalApi::class)
    suspend fun initialize(): Boolean {
        if (isMockMode) {
            Log.i("InferenceEngine", "Skipping initialization because engine is in Mock Mode.")
            return true
        }
        return withContext(Dispatchers.IO) {
            Log.i("InferenceEngine", "=== Starting Inference Engine Initialization ===")
            
            // 1. Log System and Hardware Diagnostics
            try {
                val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                val memInfo = android.app.ActivityManager.MemoryInfo()
                actManager?.getMemoryInfo(memInfo)
                val totalMem = memInfo.totalMem / (1024 * 1024)
                val availMem = memInfo.availMem / (1024 * 1024)
                val isLowMem = memInfo.lowMemory
                
                Log.i("InferenceEngine", "--- Device System Diagnostics ---")
                Log.i("InferenceEngine", "Manufacturer: ${android.os.Build.MANUFACTURER}")
                Log.i("InferenceEngine", "Brand: ${android.os.Build.BRAND}")
                Log.i("InferenceEngine", "Model: ${android.os.Build.MODEL}")
                Log.i("InferenceEngine", "Hardware: ${android.os.Build.HARDWARE}")
                Log.i("InferenceEngine", "Product: ${android.os.Build.PRODUCT}")
                Log.i("InferenceEngine", "Board: ${android.os.Build.BOARD}")
                Log.i("InferenceEngine", "Supported ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}")
                Log.i("InferenceEngine", "Android SDK API: ${android.os.Build.VERSION.SDK_INT}")
                Log.i("InferenceEngine", "RAM: Total=${totalMem}MB, Available=${availMem}MB, LowMemoryStatus=$isLowMem")
                Log.i("InferenceEngine", "---------------------------------")
            } catch (diagEx: Exception) {
                Log.w("InferenceEngine", "Failed to retrieve device system diagnostics: ${diagEx.message}")
            }

            if (loraAdapterPath != null) {
                val loraDir = File(loraAdapterPath)
                val exists = loraDir.exists()
                val isDir = loraDir.isDirectory
                val files = if (exists) loraDir.list()?.joinToString(", ") ?: "none" else "n/a"
                Log.i("InferenceEngine", "LoRA adapter path specified: $loraAdapterPath (Exists=$exists, IsDirectory=$isDir, Files=$files)")
            }

            try {
                com.google.ai.edge.litertlm.ExperimentalFlags.visualTokenBudget = 140
                Log.i("InferenceEngine", "Successfully set ExperimentalFlags.visualTokenBudget = 140")
            } catch (e: Exception) {
                Log.w("InferenceEngine", "Failed to set ExperimentalFlags: ${e.message}", e)
            }

            val isEmulator = android.os.Build.HARDWARE.contains("goldfish")
                    || android.os.Build.HARDWARE.contains("ranchu")
                    || android.os.Build.PRODUCT.contains("sdk")
                    || android.os.Build.MODEL.contains("Emulator")
                    || android.os.Build.MODEL.contains("google_sdk")

            Log.i("InferenceEngine", "Is Running on Emulator: $isEmulator")

            if (!isEmulator) {
                // 1. Try GPU first (recommended for most real devices running VLM)
                try {
                    Log.i("InferenceEngine", "Attempting LiteRT-LM Engine initialization on GPU...")
                    Log.i("InferenceEngine", "GPU Config: modelPath=$modelPath, maxNumTokens=512, visionBackend=Backend.CPU(), cacheDir=${context.cacheDir.absolutePath}")
                    val config = EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.GPU(),
                        visionBackend = Backend.CPU(), // Use CPU for vision to prevent Qualcomm GPU delegate crashes
                        maxNumTokens = 512,
                        cacheDir = context.cacheDir.absolutePath
                    )
                    val gpuEngine = Engine(config)
                    Log.i("InferenceEngine", "Calling Engine.initialize() on GPU...")
                    val startTime = System.currentTimeMillis()
                    gpuEngine.initialize()
                    val duration = System.currentTimeMillis() - startTime
                    engine = gpuEngine
                    isInitialized = true
                    isMockMode = false
                    Log.i("InferenceEngine", "LiteRT-LM Engine initialized successfully with GPU acceleration in ${duration}ms.")
                    return@withContext true
                } catch (e: Exception) {
                    Log.e("InferenceEngine", "GPU initialization failed: ${e.message}. Falling back to NPU.", e)
                }

                // 2. Try NPU second (uses hardware acceleration on chips supporting it)
                try {
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    Log.i("InferenceEngine", "Attempting LiteRT-LM Engine initialization on NPU...")
                    Log.i("InferenceEngine", "NPU Config: modelPath=$modelPath, nativeLibraryDir=$nativeLibDir, maxNumTokens=512, cacheDir=${context.cacheDir.absolutePath}")
                    val config = EngineConfig(
                        modelPath = modelPath,
<<<<<<< Updated upstream
                        backend = Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir),
                        visionBackend = Backend.GPU(),
                        maxNumTokens = 1024,
=======
                        backend = Backend.NPU(nativeLibraryDir = nativeLibDir),
                        visionBackend = Backend.CPU(),
                        maxNumTokens = 512,
>>>>>>> Stashed changes
                        cacheDir = context.cacheDir.absolutePath
                    )
                    val npuEngine = Engine(config)
                    Log.i("InferenceEngine", "Calling Engine.initialize() on NPU...")
                    val startTime = System.currentTimeMillis()
                    npuEngine.initialize()
                    val duration = System.currentTimeMillis() - startTime
                    engine = npuEngine
                    isInitialized = true
                    isMockMode = false
                    Log.i("InferenceEngine", "LiteRT-LM Engine initialized successfully with NPU acceleration in ${duration}ms.")
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
                Log.i("InferenceEngine", "CPU Config: modelPath=$modelPath, maxNumTokens=384, cacheDir=${context.cacheDir.absolutePath}")
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
<<<<<<< Updated upstream
                    visionBackend = Backend.GPU(),
                    maxNumTokens = 1024,
=======
                    visionBackend = Backend.CPU(),
                    maxNumTokens = 384,
>>>>>>> Stashed changes
                    cacheDir = context.cacheDir.absolutePath
                )
                val cpuEngine = Engine(config)
                Log.i("InferenceEngine", "Calling Engine.initialize() on CPU...")
                val startTime = System.currentTimeMillis()
                cpuEngine.initialize()
                val duration = System.currentTimeMillis() - startTime
                engine = cpuEngine
                isInitialized = true
                isMockMode = false
                Log.i("InferenceEngine", "LiteRT-LM Engine initialized successfully with CPU fallback in ${duration}ms.")
                return@withContext true
            } catch (e: Exception) {
                Log.e("InferenceEngine", "CPU initialization failed: ${e.message}. Running Mock Mode.", e)
                isMockMode = true
                isInitialized = true
                return@withContext true
            }
        }
    }

    /**
     * วิเคราะห์ภาพและส่ง token stream กลับมา
     * @return Pair<Flow<String>, latencyMs callback>
     */
    fun analyzeImageStream(imageBytes: ByteArray, promptText: String): Flow<String> = flow {
        Log.i("InferenceEngine", "analyzeImageStream called. Image size: ${imageBytes.size} bytes. Prompt: '$promptText'")
        
        if (!isInitialized) {
            Log.w("InferenceEngine", "Inference requested but InferenceEngine is not initialized.")
            emit("ระบบยังไม่พร้อมใช้งาน")
            return@flow
        }

        // ======== MOCK MODE ========
        if (isMockMode) {
            Log.i("InferenceEngine", "Running in Mock Mode. Simulating VLM inference...")
            kotlinx.coroutines.delay(1200)
            val mockResponse = when {
                promptText.contains("อ่าน") -> "ป้ายบอกทาง - ทางหนีไฟสีเขียวประตูด้านขวา (ความมั่นใจ: สูง)"
                promptText.contains("สิ่งของ") || promptText.contains("บนโต๊ะ") -> "แก้วน้ำและกุญแจ - อยู่ตรงกลางโต๊ะทำงาน (ความมั่นใจ: สูง)"
                promptText.contains("ข้างหน้า") || promptText.contains("กีดขวาง") -> "เก้าอี้ไม้ขวางทาง - อยู่ด้านหน้าประมาณ 1 เมตร (ความมั่นใจ: สูง)"
                else -> "สมาร์ทโฟน - ถืออยู่ในมือ (ความมั่นใจ: สูง)"
            }
            Log.i("InferenceEngine", "Mock Mode response: '$mockResponse'")
            val tokens = mockResponse.split(" ")
            for (token in tokens) {
                emit("$token ")
                kotlinx.coroutines.delay(100)
            }
            return@flow
        }

        // ======== REAL MODE ========
        // Reuse the already initialized engine to avoid loading the model from disk on every request

        val currentEngine = engine
        if (currentEngine == null) {
            Log.e("InferenceEngine", "Real Mode active but engine is null! isInitialized=$isInitialized")
            emit("เกิดข้อผิดพลาด: โมเดลไม่พร้อมใช้งาน")
            return@flow
        }

        try {
            val startTime = System.currentTimeMillis()
            var firstTokenTime = 0L
            var tokenCount = 0
            val fullPrompt = "$systemPrompt\n\nคำสั่ง: $promptText"

            // Downscale the image to 224x224 in memory specifically for the VLM model
            Log.i("InferenceEngine", "Resizing input image to 224x224 for VLM model...")
            val vlmImageBytes = resizeImageForVlm(imageBytes)

            Log.i("InferenceEngine", "Creating conversation session...")
            currentEngine.createConversation().use { conversation ->
                Log.i("InferenceEngine", "Sending message with prompt length: ${fullPrompt.length} chars, image size: ${vlmImageBytes.size} bytes...")
                val contents = Contents.of(listOf(
                    Content.ImageBytes(vlmImageBytes),
                    Content.Text(fullPrompt)
                ))

                val responseBuilder = StringBuilder()
                var isLowConfidence = false

                conversation.sendMessageAsync(contents).collect { token ->
                    val tokenStr = token.toString()
                    tokenCount++
                    
                    if (firstTokenTime == 0L) {
                        firstTokenTime = System.currentTimeMillis() - startTime
                        Log.i("InferenceEngine", "Time-to-First-Token (TTFT): ${firstTokenTime}ms")
                    }
                    
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

                val totalDuration = System.currentTimeMillis() - startTime
                val averageTokenLatency = if (tokenCount > 0) totalDuration.toFloat() / tokenCount else 0f
                Log.i("InferenceEngine", "Inference stream complete. Total Duration: ${totalDuration}ms, Total Tokens: $tokenCount, Average Token Latency: ${"%.2f".format(averageTokenLatency)}ms, LowConfidence=$isLowConfidence")
                Log.i("InferenceEngine", "Full VLM response: '${responseBuilder.toString()}'")

                if (isLowConfidence) {
                    Log.w("InferenceEngine", "Low confidence detected in VLM response. Emitting suggestion overlay.")
                    emit("\n[!] ระบบไม่แน่ใจในภาพที่เห็น กรุณาปรับมุมกล้องและลองใหม่อีกครั้ง")
                }
            }
        } catch (e: Exception) {
            Log.e("InferenceEngine", "Inference error during analyzeImageStream: ${e.message}", e)
            emit("เกิดข้อผิดพลาดในการประมวลผลโมเดล")
        }
    }

    private fun resizeImageForVlm(imageBytes: ByteArray): ByteArray {
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return imageBytes
            val scaled = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            
            if (scaled != bitmap) {
                scaled.recycle()
            }
            bitmap.recycle()
            
            val resized = out.toByteArray()
            Log.i("InferenceEngine", "Image resized successfully from ${imageBytes.size} to ${resized.size} bytes (224x224).")
            resized
        } catch (e: Exception) {
            Log.w("InferenceEngine", "Failed to resize image for VLM: ${e.message}. Using original bytes.", e)
            imageBytes
        }
    }

    fun release() {
        Log.i("InferenceEngine", "Releasing InferenceEngine. MockMode=$isMockMode, EngineIsNull=${engine == null}")
        if (!isMockMode) {
            try {
                engine?.close()
                engine = null
                Log.i("InferenceEngine", "Successfully closed LiteRT-LM Engine.")
            } catch (e: Exception) {
                Log.e("InferenceEngine", "Failed to close engine: ${e.message}", e)
            }
        }
        isInitialized = false
    }
}
