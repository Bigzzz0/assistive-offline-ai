package com.assistive.system.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * รูปแบบการรายงานความคืบหน้าการดาวน์โหลด
 */
data class DownloadProgress(
    val fileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progressPercent: Float,
    val isComplete: Boolean = false,
    val error: String? = null
)

/**
 * รายการไฟล์โมเดลที่ต้องดาวน์โหลด
 */
enum class ModelFile(
    val displayName: String,
    val fileName: String,
    val url: String,
    val subDir: String = ""
) {
    // Sherpa-ONNX Thai ASR — Zipformer model (sherpa-onnx-streaming-zipformer-thai-2024-06-20)
    ASR_ENCODER(
        displayName = "ASR Encoder (Thai)",
        fileName = "encoder.onnx",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-thai-2024-06-20.tar.bz2",
        subDir = "sherpa-onnx-thai"
    ),
    // NOTE: Encoder, Decoder, Joiner, Tokens are packed in a single tar.bz2 archive.
    // We download the archive once then extract. The enum entry above handles the whole pack.
    // For simplicity in this implementation, we download individual model files from a mirror.
    ASR_DECODER(
        displayName = "ASR Decoder (Thai)",
        fileName = "decoder.onnx",
        url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-thai-2024-06-20/resolve/main/decoder.onnx",
        subDir = "sherpa-onnx-thai"
    ),
    ASR_JOINER(
        displayName = "ASR Joiner (Thai)",
        fileName = "joiner.onnx",
        url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-thai-2024-06-20/resolve/main/joiner.onnx",
        subDir = "sherpa-onnx-thai"
    ),
    ASR_TOKENS(
        displayName = "ASR Tokens (Thai)",
        fileName = "tokens.txt",
        url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-thai-2024-06-20/resolve/main/tokens.txt",
        subDir = "sherpa-onnx-thai"
    );
    // NOTE: VLM model (gemma_vlm.litertlm, ~1.5GB) must be downloaded separately
    // via ADB or provided manually due to Hugging Face Gemma license requirements:
    //   adb push gemma-4-E2B-it.litertlm /data/user/0/com.assistive.system/files/gemma_vlm.litertlm
    // Instructions are shown in the Model Manager UI.
}

/**
 * ดาวน์โหลดไฟล์โมเดลพร้อม progress tracking และ resume capability
 */
object ModelDownloader {

    private val TAG = "ModelDownloader"
    private val CONNECT_TIMEOUT = 30_000  // 30 seconds
    private val READ_TIMEOUT = 60_000     // 60 seconds
    private val BUFFER_SIZE = 8192        // 8 KB buffer

    /**
     * ดาวน์โหลดไฟล์เดี่ยว ส่ง Flow<DownloadProgress> แบบ streaming
     * รองรับ resume ถ้าไฟล์ที่โหลดค้างยังอยู่
     */
    fun downloadFile(
        url: String,
        destFile: File,
        displayName: String
    ): Flow<DownloadProgress> = flow {
        val tempFile = File(destFile.parent, destFile.name + ".tmp")
        val startByte = if (tempFile.exists()) tempFile.length() else 0L

        Log.i(TAG, "Starting download: $displayName (resume from byte $startByte)")
        emit(DownloadProgress(displayName, startByte, -1L, 0f))

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.setRequestProperty("User-Agent", "AssistiveOfflineAI/1.0")

            // Resume support via Range header
            if (startByte > 0) {
                connection.setRequestProperty("Range", "bytes=$startByte-")
            }
            connection.connect()

            val responseCode = connection.responseCode
            val totalBytes = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                startByte + connection.contentLengthLong
            } else {
                connection.contentLengthLong
            }

            val isResume = responseCode == HttpURLConnection.HTTP_PARTIAL
            Log.i(TAG, "Response $responseCode, total=$totalBytes bytes, resume=$isResume")

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(tempFile, isResume)

            var bytesDownloaded = startByte
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                bytesDownloaded += bytesRead
                val progress = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
                emit(DownloadProgress(displayName, bytesDownloaded, totalBytes, progress))
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            connection.disconnect()

            // Rename temp → final file
            destFile.parentFile?.mkdirs()
            tempFile.renameTo(destFile)

            Log.i(TAG, "Download complete: $displayName → ${destFile.absolutePath}")
            emit(DownloadProgress(displayName, bytesDownloaded, totalBytes, 1f, isComplete = true))

        } catch (e: Exception) {
            val msg = "Download failed for $displayName: ${e.message}"
            Log.e(TAG, msg, e)
            emit(DownloadProgress(displayName, startByte, -1L, 0f, error = msg))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * ดาวน์โหลดโมเดล ASR (ทุกไฟล์)
     */
    fun getAsrModelFiles(): List<ModelFile> = listOf(
        ModelFile.ASR_DECODER,
        ModelFile.ASR_JOINER,
        ModelFile.ASR_TOKENS
    )
}
