package com.assistive.system.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.assistive.system.logging.AppLogger as Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioPipeline(
    private val context: Context,
    private val modelDirPath: String = context.filesDir.absolutePath + "/sherpa-onnx-thai"
) : TextToSpeech.OnInitListener {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private var recognizer: OfflineRecognizer? = null
    private var isListening = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var keywordCallback: ((String) -> Unit)? = null

    // Audio Focus Request for Audio Ducking (API 26+)
    private var focusRequest: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(context, this)
    }

    // ===== Public API =====

    /** ตรวจสอบว่า ASR โมเดลพร้อมใช้งานจริงหรือไม่ */
    fun isAsrReady(): Boolean = recognizer != null

    /** โหลด ASR model ใหม่หลังดาวน์โหลดสำเร็จ (ไม่ต้อง restart แอป) */
    suspend fun reinitializeRecognizer() {
        val wasListening = isListening
        if (wasListening) stopListening()
        
        withContext(Dispatchers.IO) {
            recognizer?.release()
            recognizer = null
            initializeRecognizer()
        }
        
        if (wasListening && keywordCallback != null) {
            startListening(keywordCallback!!)
        }
        Log.i("AudioPipeline", "ASR Reinitialized. Ready=${isAsrReady()}")
    }

    // ===== Internal Setup =====

    suspend fun initializeRecognizer() = withContext(Dispatchers.IO) {
        val encoderFile = File(modelDirPath, "encoder.onnx")
        if (!encoderFile.exists()) {
            Log.w("AudioPipeline", "Sherpa-ONNX encoder not found at $modelDirPath. ASR in simulation mode.")
            return@withContext
        }

        try {
            val config = OfflineRecognizerConfig().apply {
                modelConfig.transducer.apply {
                    encoder = File(modelDirPath, "encoder.onnx").absolutePath
                    decoder = File(modelDirPath, "decoder.onnx").absolutePath
                    joiner = File(modelDirPath, "joiner.onnx").absolutePath
                }
                modelConfig.tokens = File(modelDirPath, "tokens.txt").absolutePath
                modelConfig.numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
                modelConfig.provider = "cpu"
                featConfig.sampleRate = 16000
                featConfig.featureDim = 80
                decodingMethod = "greedy_search"
            }
            recognizer = OfflineRecognizer(null, config)
            Log.i("AudioPipeline", "Sherpa-ONNX Thai ASR (OfflineRecognizer) initialized successfully.")
            
            // If listen was requested before initialization finished, start the recording thread now
            if (isListening && keywordCallback != null) {
                Log.i("AudioPipeline", "ASR initialized while listening was active. Starting microphone thread.")
                startListeningThread(keywordCallback!!)
            }
        } catch (e: Exception) {
            Log.e("AudioPipeline", "Failed to initialize Sherpa-ONNX: ${e.message}", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("th"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("AudioPipeline", "Thai TTS not supported. Falling back to English.")
                tts?.setLanguage(Locale.US)
            }
            isTtsReady = true
            Log.i("AudioPipeline", "TTS initialized successfully.")
        } else {
            Log.e("AudioPipeline", "TTS initialization failed.")
        }
    }

    fun startListening(onKeywordDetected: (String) -> Unit) {
        keywordCallback = onKeywordDetected
        if (isListening) return
        isListening = true

        val rec = recognizer
        if (rec == null) {
            Log.i("AudioPipeline", "ASR Simulation Mode: Use buttons to trigger commands.")
            return
        }

        startListeningThread(onKeywordDetected)
    }

    private fun startListeningThread(onKeywordDetected: (String) -> Unit) {
        if (recordingThread != null) return // Already running
        val rec = recognizer ?: return

        val sampleRate = 16000
        val audioSource = MediaRecorder.AudioSource.MIC
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            audioRecord = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)
            audioRecord?.startRecording()
            recordingThread = thread(start = true) {
                val buffer = ShortArray(bufferSize)
                val maxAudioSamples = 16000 * 10 // Max 10 seconds of audio
                val audioDataBuffer = FloatArray(maxAudioSamples)
                var audioDataSize = 0
                var silenceFrames = 0
                val maxSilenceFrames = 40 // ~0.8 second of silence at 20ms frames
                var isSpeaking = false
                
                while (isListening) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readBytes > 0) {
                        val floatBuffer = FloatArray(readBytes) { i -> buffer[i] / 32768.0f }
                        
                        // Calculate MAE (Mean Absolute Error) to check for speech vs silence
                        var sum = 0.0f
                        for (i in 0 until readBytes) {
                            sum += kotlin.math.abs(floatBuffer[i])
                        }
                        val mae = sum / readBytes
                        
                        // Speech detection threshold
                        val isCurrentFrameSpeech = mae >= 0.015f
                        
                        if (isCurrentFrameSpeech) {
                            if (!isSpeaking) {
                                Log.d("AudioPipeline", "Speech activity detected (mae = ${String.format("%.4f", mae)})")
                                isSpeaking = true
                            }
                            silenceFrames = 0
                        } else {
                            if (isSpeaking) {
                                silenceFrames++
                            }
                        }
                        
                        if (isSpeaking) {
                            // Accumulate audio samples only when active speech is detected
                            if (audioDataSize + readBytes <= maxAudioSamples) {
                                System.arraycopy(floatBuffer, 0, audioDataBuffer, audioDataSize, readBytes)
                                audioDataSize += readBytes
                            } else {
                                // Reset if buffer overflow
                                audioDataSize = 0
                                isSpeaking = false
                                silenceFrames = 0
                            }
                            
                            // If user stops speaking (silence detected for ~0.8s), decode the utterance
                            if (silenceFrames >= maxSilenceFrames) {
                                if (audioDataSize > 8000) { // At least 0.5 seconds of audio
                                    val stream = rec.createStream()
                                    val activeSamples = FloatArray(audioDataSize)
                                    System.arraycopy(audioDataBuffer, 0, activeSamples, 0, audioDataSize)
                                    stream.acceptWaveform(activeSamples, sampleRate)
                                    rec.decode(stream)
                                    val text = rec.getResult(stream).text.trim().lowercase()
                                    if (text.isNotEmpty()) {
                                        Log.i("AudioPipeline", "ASR recognized: '$text'")
                                        if (matchKeyword(text)) {
                                            onKeywordDetected(text)
                                        }
                                    }
                                    stream.release()
                                }
                                // Reset for next utterance
                                audioDataSize = 0
                                isSpeaking = false
                                silenceFrames = 0
                            }
                        }
                    }
                    Thread.sleep(20)
                }
            }
            Log.i("AudioPipeline", "Microphone listening started (Real ASR mode with OfflineRecognizer).")
        } catch (e: SecurityException) {
            Log.e("AudioPipeline", "Audio permission not granted: ${e.message}")
            isListening = false
        } catch (e: Exception) {
            Log.e("AudioPipeline", "Recording error: ${e.message}", e)
            isListening = false
        }
    }

    /**
     * ตรวจสอบ keyword ภาษาไทยตาม Proposal Section 5.2
     * รองรับ: อ่าน, ดู, หยุด, ข้างหน้า, สิ่งของ, บนโต๊ะ, ช่วย, กีดขวาง, อันตราย
     */
    private fun matchKeyword(text: String): Boolean {
        val keywords = listOf(
            "อ่าน", "ดู", "หยุด", "ข้างหน้า",
            "สิ่งของ", "บนโต๊ะ", "ช่วย",
            "กีดขวาง", "อันตราย", "มีอะไร"
        )
        return keywords.any { text.contains(it) }
    }

    fun stopListening() {
        isListening = false
        audioRecord?.apply {
            try {
                if (state == AudioRecord.STATE_INITIALIZED) stop()
                release()
            } catch (e: Exception) {
                Log.e("AudioPipeline", "Error stopping AudioRecord: ${e.message}")
            }
        }
        audioRecord = null
        recordingThread = null
        Log.i("AudioPipeline", "Microphone listening stopped.")
    }

    fun speak(text: String, onComplete: () -> Unit = {}) {
        if (!isTtsReady) {
            Log.w("AudioPipeline", "TTS not ready yet. Text: $text")
            onComplete()
            return
        }

        requestAudioFocusForDucking()

        val utteranceId = UUID.randomUUID().toString()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                abandonAudioFocus()
                onComplete()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                abandonAudioFocus()
                onComplete()
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            val params = HashMap<String, String>()
            params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = utteranceId
            @Suppress("DEPRECATION")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params)
        }
    }

    private fun requestAudioFocusForDucking() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAcceptsDelayedFocusGain(false)
                .build()
            focusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun stopSpeaking() {
        if (isTtsReady) {
            tts?.stop()
            abandonAudioFocus()
        }
    }

    fun release() {
        stopListening()
        tts?.shutdown()
        tts = null
        recognizer?.release()
        recognizer = null
    }
}
