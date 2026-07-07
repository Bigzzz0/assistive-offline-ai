package com.assistive.arcoreyolo.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private val TAG = "TtsManager"
    private var tts: TextToSpeech? = null
    var isReady = false
        private set

    private var currentLanguage = Locale("th")

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            setLanguage("th")
            isReady = true
            Log.i(TAG, "TextToSpeech initialized successfully.")
        } else {
            Log.e(TAG, "TextToSpeech initialization failed.")
        }
    }

    fun setLanguage(langCode: String) {
        val locale = if (langCode.lowercase() == "th") Locale("th") else Locale.US
        currentLanguage = locale
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "Language $langCode not supported. Falling back to English.")
            tts?.setLanguage(Locale.US)
            currentLanguage = Locale.US
        }
    }

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH, onComplete: () -> Unit = {}) {
        if (text.isBlank() || tts == null || !isReady) {
            onComplete()
            return
        }

        val utteranceId = UUID.randomUUID().toString()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                onComplete()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onComplete()
            }
        })

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            tts?.speak(text, queueMode, null, utteranceId)
        } else {
            val params = HashMap<String, String>()
            params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = utteranceId
            @Suppress("DEPRECATION")
            tts?.speak(text, queueMode, params)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
