package com.assistive.system.download

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * ตรวจสอบและจัดการสถานะไฟล์โมเดล
 */
class ModelManager(private val context: Context) {

    // Base dirs
    val vlmModelFile: File get() = File(context.filesDir, "gemma_vlm.litertlm")
    val asrModelDir: File get() = File(context.filesDir, "sherpa-onnx-thai")

    // Required ASR files
    private val asrRequiredFiles = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")

    /**
     * ตรวจสอบว่า VLM model พร้อมใช้งานหรือไม่
     */
    fun isVlmReady(): Boolean = vlmModelFile.exists() && vlmModelFile.length() > 100_000_000L // > 100 MB

    /**
     * ตรวจสอบว่า ASR model ครบหรือไม่
     */
    fun isAsrReady(): Boolean {
        if (!asrModelDir.exists()) return false
        return asrRequiredFiles.all { fileName ->
            val file = File(asrModelDir, fileName)
            file.exists() && file.length() > 0
        }
    }

    /**
     * ตรวจสอบว่าโมเดลทั้งหมดพร้อมหรือไม่
     */
    fun areAllModelsReady(): Boolean = isVlmReady() && isAsrReady()

    /**
     * ขนาดของไฟล์โมเดล VLM ที่มีอยู่
     */
    fun getVlmFileSizeMB(): Long = if (vlmModelFile.exists()) vlmModelFile.length() / (1024 * 1024) else 0L

    /**
     * ขนาดรวมของไฟล์ ASR ที่มีอยู่ (MB)
     */
    fun getAsrFileSizeMB(): Long {
        if (!asrModelDir.exists()) return 0L
        return asrModelDir.listFiles()?.sumOf { it.length() }?.div(1024 * 1024) ?: 0L
    }

    /**
     * พื้นที่ว่างในอุปกรณ์ (MB)
     */
    fun getAvailableSpaceMB(): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)
    }

    /**
     * ต้องการพื้นที่ขั้นต่ำประมาณ 2.5 GB สำหรับ VLM + ASR
     */
    fun hasEnoughSpace(): Boolean = getAvailableSpaceMB() > 2600L // ~2.5 GB

    /**
     * ลบไฟล์ ASR ทั้งหมด (reset)
     */
    fun deleteAsrModels() {
        asrModelDir.deleteRecursively()
    }

    /**
     * ลบ VLM model
     */
    fun deleteVlmModel() {
        vlmModelFile.delete()
    }

    /**
     * ส่งคืน destination file สำหรับ ModelFile enum
     */
    fun getDestFile(modelFile: ModelFile): File {
        val dir = if (modelFile.subDir.isNotEmpty()) {
            File(context.filesDir, modelFile.subDir)
        } else {
            context.filesDir
        }
        dir.mkdirs()
        return File(dir, modelFile.fileName)
    }

    /**
     * รายละเอียดสถานะโมเดลสำหรับแสดงใน UI
     */
    fun getStatusSummary(): String {
        val vlm = if (isVlmReady()) "✅ VLM พร้อม (${getVlmFileSizeMB()} MB)" else "❌ VLM: ต้องโหลดด้วย ADB"
        val asr = if (isAsrReady()) "✅ ASR พร้อม (${getAsrFileSizeMB()} MB)" else "❌ ASR: ยังไม่ได้ดาวน์โหลด"
        val space = "💾 พื้นที่ว่าง: ${getAvailableSpaceMB()} MB"
        return "$vlm\n$asr\n$space"
    }
}
