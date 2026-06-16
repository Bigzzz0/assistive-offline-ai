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
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class AudioPipeline(
    private val context: Context,
    private val modelDirPath: String = context.filesDir.absolutePath + "/sherpa-onnx-thai"
) : TextToSpeech.OnInitListener {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private var recognizer: OnlineRecognizer? = null
    private var isListening = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    // Audio Focus Request for Audio Ducking (API 26+)
    private var focusRequest: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(context, this)
        initializeRecognizer()
    }

    private fun initializeRecognizer() {
        val modelFile = File(modelDirPath, "encoder.onnx")
        if (!modelFile.exists()) {
            Log.w("AudioPipeline", "Sherpa-ONNX model files not found at $modelDirPath. ASR will run in simulation mode.")
            return
        }

        try {
            val config = OnlineRecognizerConfig().apply {
                modelConfig.transducer.apply {
                    encoder = File(modelDirPath, "encoder.onnx").absolutePath
                    decoder = File(modelDirPath, "decoder.onnx").absolutePath
                    joiner = File(modelDirPath, "joiner.onnx").absolutePath
                }
                modelConfig.tokens = File(modelDirPath, "tokens.txt").absolutePath
                modelConfig.numThreads = 4
                modelConfig.provider = "cpu" // fallback to CPU for general compatibility
                featConfig.sampleRate = 16000
                featConfig.featureDim = 80
                enableEndpoint = true
            }
            recognizer = OnlineRecognizer(config)
            Log.i("AudioPipeline", "Sherpa-ONNX Recognizer initialized successfully.")
        } catch (e: Exception) {
            Log.e("AudioPipeline", "Failed to initialize Sherpa-ONNX: ${e.message}", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("th"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("AudioPipeline", "Thai language is not supported for TTS. Falling back to English.")
                tts?.setLanguage(Locale.US)
            }
            isTtsReady = true
            Log.i("AudioPipeline", "TTS initialized successfully.")
        } else {
            Log.e("AudioPipeline", "TTS initialization failed.")
        }
    }

    fun startListening(onKeywordDetected: (String) -> Unit) {
        if (isListening) return
        isListening = true

        val rec = recognizer
        if (rec == null) {
            Log.i("AudioPipeline", "ASR Running in simulation mode. Tap screen to simulate commands.")
            return
        }

        val sampleRate = 16000
        val audioSource = MediaRecorder.AudioSource.MIC
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            audioRecord = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)
            val stream: OnlineStream = rec.createStream()

            audioRecord?.startRecording()
            recordingThread = thread(start = true) {
                val buffer = ShortArray(bufferSize)
                while (isListening) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readBytes > 0) {
                        val floatBuffer = FloatArray(readBytes) { i -> buffer[i] / 32768.0f }
                        stream.acceptWaveform(floatBuffer, sampleRate)
                        
                        while (rec.isReady(stream)) {
                            rec.decode(stream)
                        }
                        
                        val text = rec.getResult(stream).text.trim().lowercase()
                        if (text.isNotEmpty()) {
                            Log.d("AudioPipeline", "Recognized speech: $text")
                            // Check Thai keywords
                            if (text.contains("อ่าน") || text.contains("ดู") || text.contains("หยุด") || text.contains("ข้างหน้า")) {
                                onKeywordDetected(text)
                            }
                        }
                        
                        if (rec.isEndpoint(stream)) {
                            rec.reset(stream)
                        }
                    }
                    Thread.sleep(20)
                }
                stream.release()
            }
            Log.i("AudioPipeline", "Microphone listening started.")
        } catch (e: SecurityException) {
            Log.e("AudioPipeline", "Permission not granted to record audio: ${e.message}")
            isListening = false
        } catch (e: Exception) {
            Log.e("AudioPipeline", "Error during recording setup: ${e.message}", e)
            isListening = false
        }
    }

    fun stopListening() {
        isListening = false
        audioRecord?.apply {
            try {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
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
            Log.w("AudioPipeline", "TTS not ready yet. Logging output: $text")
            onComplete()
            return
        }

        // Apply Audio Ducking
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
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
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

    fun release() {
        stopListening()
        tts?.shutdown()
        tts = null
        recognizer?.release()
        recognizer = null
    }
}
