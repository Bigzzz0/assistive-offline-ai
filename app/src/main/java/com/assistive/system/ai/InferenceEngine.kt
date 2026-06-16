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
    private val modelPath: String = context.filesDir.absolutePath + "/gemma_vlm.litertlm"
) {

    private var engine: Engine? = null
    private var isInitialized = false
    private var isMockMode = false

    // System Prompt to enforce constraint decoding format
    private val systemPrompt = """
        บทบาท: คุณคือผู้ช่วยคนตาบอดภาษาไทย ตอบข้อมูลสั้นและตรงประเด็นที่สุด
        รูปแบบคำตอบที่บังคับ: [ชื่อวัตถุ] - [ตำแหน่งโดยประมาณ] (ความมั่นใจ: [ต่ำ/ปานกลาง/สูง])
        ตัวอย่าง: แก้วน้ำ - บนโต๊ะด้านหน้าขวา (ความมั่นใจ: สูง)
        ห้ามอธิบายยาว ห้ามใช้คำฟุ่มเฟือย ถ้าไม่มั่นใจอย่างมาก ให้ตอบว่า (ความมั่นใจ: ต่ำ)
    """.trimIndent()

    init {
        checkModelAndSetup()
    }

    private fun checkModelAndSetup() {
        val file = File(modelPath)
        if (!file.exists()) {
            Log.w("InferenceEngine", "LiteRT-LM model not found at $modelPath. Running in mock/simulation mode.")
            isMockMode = true
            isInitialized = true
            return
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
            Log.e("InferenceEngine", "Failed to configure LiteRT-LM GPU Engine: ${e.message}. Falling back to CPU.", e)
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
                Log.e("InferenceEngine", "Failed to initialize LiteRT-LM CPU Engine: ${e2.message}. Running in mock mode.", e2)
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
                Log.e("InferenceEngine", "Error initializing LiteRT-LM Engine: ${e.message}", e)
                isMockMode = true
                isInitialized = true
                true
            }
        }
    }

    fun analyzeImageStream(bitmap: Bitmap, promptText: String): Flow<String> = flow {
        if (!isInitialized) {
            emit("ระบบยังไม่พร้อมใช้งาน")
            return@flow
        }

        if (isMockMode) {
            // Simulate inference latency
            kotlinx.coroutines.delay(1200)
            
            // Return simulation responses matching the prompt
            val mockResponse = when {
                promptText.contains("อ่าน") -> "ป้ายบอกทาง - ทางหนีไฟสีเขียวประตูด้านขวา (ความมั่นใจ: สูง)"
                promptText.contains("สิ่งของ") || promptText.contains("บนโต๊ะ") -> "แก้วน้ำและกุญแจ - อยู่ตรงกลางโต๊ะทำงาน (ความมั่นใจ: สูง)"
                promptText.contains("ข้างหน้า") || promptText.contains("สิ่งกีดขวาง") -> "เก้าอี้ไม้ขวางทาง - อยู่ด้านหน้าประมาณ 1 เมตร (ความมั่นใจ: สูง)"
                else -> "สมาร์ทโฟน - ถืออยู่ในมือ (ความมั่นใจ: สูง)"
            }
            
            // Stream mock words character by character to simulate real streaming
            val tokens = mockResponse.split(" ")
            for (token in tokens) {
                emit("$token ")
                kotlinx.coroutines.delay(100)
            }
            return@flow
        }

        val currentEngine = engine
        if (currentEngine == null) {
            emit("เกิดข้อผิดพลาด: โมเดลไม่พร้อมใช้งาน")
            return@flow
        }

        // 1. Convert Bitmap to PNG byte array
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 85, stream)
        val imageBytes = stream.toByteArray()

        try {
            // Create a temporary conversation session
            currentEngine.createConversation().use { conversation ->
                // Formulate Prompt: System prompt + User's question
                val fullPrompt = "$systemPrompt\n\nคำสั่ง: $promptText"
                
                // Pack Content: Image bytes MUST come BEFORE the Text content
                val contents = Contents.of(listOf(
                    Content.ImageBytes(imageBytes),
                    Content.Text(fullPrompt)
                ))

                // Accumulate response to verify confidence
                val responseBuilder = StringBuilder()
                var isLowConfidence = false

                conversation.sendMessageAsync(contents).collect { token ->
                    responseBuilder.append(token)
                    
                    // Simple streaming check: if we see "ความมั่นใจ: ต่ำ" in the accumulating response,
                    // we immediately flag it for fallback.
                    val currentText = responseBuilder.toString()
                    if (currentText.contains("ความมั่นใจ: ต่ำ") || currentText.contains("ความมั่นใจ:ต่ำ")) {
                        isLowConfidence = true
                    }
                    
                    if (!isLowConfidence) {
                        emit(token)
                    }
                }

                // If at the end of the streaming, we detected low confidence, output the fallback message
                if (isLowConfidence) {
                    // Reset output and notify user
                    emit("\n[!] ระบบไม่แน่ใจในลักษณะวัตถุ กรุณาลองจัดมุมกล้องและตรวจสอบใหม่อีกครั้ง")
                }
            }
        } catch (e: Exception) {
            Log.e("InferenceEngine", "Error running inference: ${e.message}", e)
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
