package com.example.core.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TTSHelper(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var currentLocale: Locale = Locale.US

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

    fun setLanguage(locale: Locale) {
        currentLocale = locale
        if (isInitialized) {
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("TTSHelper", "Language $locale not supported, keeping previous")
            }
        }
    }

    fun speak(text: String, locale: Locale? = null) {
        if (text.isBlank()) return
        if (isInitialized) {
            if (locale != null && locale != currentLocale) {
                tts?.setLanguage(locale)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "LexiReadPronunciation")
        } else {
            Log.w("TTSHelper", "TTS called before initialization completed.")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}

