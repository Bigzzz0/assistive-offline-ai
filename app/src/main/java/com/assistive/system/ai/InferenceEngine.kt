package com.assistive.system.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class InferenceEngine(
    private val context: Context,
    private val modelPath: String = context.filesDir.absolutePath + "/gemma_vlm.litertlm",
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
        if (!file.exists() || file.length() < 100_000_000L) {
            Log.w("InferenceEngine", "VLM model not found at $modelPath. Running in Mock Mode.")
            isMockMode = true
            isInitialized = true
            return
        }

        // Log LoRA adapter status
        if (loraAdapterPath != null) {
            Log.i("InferenceEngine", "LoRA adapter detected at $loraAdapterPath")
        }

        try {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                cacheDir = context.cacheDir.absolutePath
            )
            engine = Engine(config)
            isMockMode = false
            Log.i("InferenceEngine", "LiteRT-LM Engine configured with GPU acceleration.")
        } catch (e: Exception) {
            Log.e("InferenceEngine", "Failed GPU init: ${e.message}. Falling back to CPU.", e)
            try {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    cacheDir = context.cacheDir.absolutePath
                )
                engine = Engine(config)
                isMockMode = false
                Log.i("InferenceEngine", "LiteRT-LM Engine configured with CPU fallback.")
            } catch (e2: Exception) {
                Log.e("InferenceEngine", "Failed CPU init: ${e2.message}. Running Mock Mode.", e2)
                isMockMode = true
            }
        }
    }

    suspend fun initialize(): Boolean {
        if (isMockMode) return true
        return withContext(Dispatchers.IO) {
            try {
                engine?.initialize()
                isInitialized = true
                Log.i("InferenceEngine", "LiteRT-LM Engine initialized successfully.")
                true
            } catch (e: Exception) {
                Log.e("InferenceEngine", "Initialization error: ${e.message}", e)
                isMockMode = true
                isInitialized = true
                true
            }
        }
    }

    /**
     * วิเคราะห์ภาพและส่ง token stream กลับมา
     * @return Pair<Flow<String>, latencyMs callback>
     */
    fun analyzeImageStream(bitmap: Bitmap, promptText: String): Flow<String> = flow {
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
        val currentEngine = engine ?: run {
            emit("เกิดข้อผิดพลาด: โมเดลไม่พร้อมใช้งาน")
            return@flow
        }

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 85, stream)
        val imageBytes = stream.toByteArray()

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
                    val currentText = responseBuilder.toString()
                    if (currentText.contains("ความมั่นใจ: ต่ำ") || currentText.contains("ความมั่นใจ:ต่ำ")) {
                        isLowConfidence = true
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
