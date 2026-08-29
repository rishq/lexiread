package com.lexiread.core.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TTSHelper(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext

    // The engine is bound lazily on first use. Constructing it eagerly held a
    // TextToSpeech service binding for the entire process lifetime, even for
    // users who never tapped a word.
    private var tts: TextToSpeech? = null
    @Volatile private var isInitialized = false
    private var currentLocale: Locale = Locale.US
    private val lock = Any()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(currentLocale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isInitialized = true
                Log.d("TTSHelper", "TextToSpeech successfully initialized with locale: $currentLocale")
            } else {
                Log.w("TTSHelper", "TextToSpeech: Locale $currentLocale not supported or missing data, falling back to Locale.US")
                val fallback = tts?.setLanguage(Locale.US)
                if (fallback != TextToSpeech.LANG_MISSING_DATA && fallback != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isInitialized = true
                }
            }
        } else {
            Log.e("TTSHelper", "Failed to initialize TextToSpeech (status: $status)")
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = ensureEngine()
        if (isInitialized && engine != null) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "LexiReadPronunciation")
        } else {
            // First call after creation: initialization is asynchronous, so the
            // request cannot be honoured yet.
            Log.w("TTSHelper", "TTS called before initialization completed.")
        }
    }

    /** Binds the engine on first use; safe to call repeatedly. */
    private fun ensureEngine(): TextToSpeech? {
        val existing = tts
        if (existing != null) return existing
        return synchronized(lock) {
            val current = tts
            if (current != null) {
                current
            } else {
                runCatching { TextToSpeech(appContext, this) }
                    .onFailure { Log.e("TTSHelper", "Unable to create TextToSpeech", it) }
                    .getOrNull()
                    ?.also { tts = it }
            }
        }
    }

    /** Releases the engine binding. Safe to call more than once. */
    fun shutdown() {
        synchronized(lock) {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        }
    }
}

