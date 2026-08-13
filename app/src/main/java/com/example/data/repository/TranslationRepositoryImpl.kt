package com.example.data.repository

import com.example.data.local.dao.CacheDao
import com.example.data.local.entity.TranslationCacheEntity
import com.example.data.remote.api.TranslationApi
import com.example.domain.model.TranslationResult
import com.example.domain.repository.TranslationRepository

class TranslationRepositoryImpl(
    private val translationApi: TranslationApi,
    private val cacheDao: CacheDao
) : TranslationRepository {

    override suspend fun translateText(
        text: String,
        targetLang: String
    ): Result<TranslationResult> {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            return Result.failure(IllegalArgumentException("Text is empty"))
        }

        val cacheKey = "${cleanText.lowercase()}_$targetLang"

        // 1. Check Room cache
        val cached = cacheDao.getTranslationCache(cacheKey)
        if (cached != null) {
            return Result.success(
                TranslationResult(
                    sourceText = cleanText,
                    translatedText = cached.translatedText,
                    sourceLang = "en",
                    targetLang = targetLang
                )
            )
        }

        // 2. Fetch from MyMemory / LibreTranslate API
        return runCatching {
            val response = translationApi.translate(cleanText, "en|$targetLang")
            val translated = response.responseData?.translatedText

            if (translated.isNullOrBlank() || translated.contains("QUERY LENGTH LIMIT EXCEEDED")) {
                throw Exception("Translation API limit reached")
            }

            // Cache in Room
            cacheDao.insertTranslationCache(
                TranslationCacheEntity(
                    cacheKey = cacheKey,
                    sourceText = cleanText,
                    translatedText = translated,
                    targetLang = targetLang,
                    timestamp = System.currentTimeMillis()
                )
            )

            TranslationResult(
                sourceText = cleanText,
                translatedText = translated,
                sourceLang = "en",
                targetLang = targetLang
            )
        }.recoverCatching {
            // Smart offline rule fallback dictionary for key literary words
            val fallbackTranslation = getOfflineFallbackTranslation(cleanText, targetLang)
            TranslationResult(
                sourceText = cleanText,
                translatedText = fallbackTranslation,
                sourceLang = "en",
                targetLang = targetLang
            )
        }
    }

    private fun getOfflineFallbackTranslation(text: String, targetLang: String): String {
        val word = text.lowercase().trim().filter { it.isLetter() }
        val ruMap = mapOf(
            "reluctant" to "неохотный / неохотно сделанный",
            "universal" to "всеобщий / универсальный",
            "acknowledged" to "признанный / общепринятый",
            "possession" to "владение / обладание",
            "fortune" to "состояние / удача",
            "neighbourhood" to "соседство / окрестности",
            "tiresome" to "утомительный / надоедливый",
            "establishment" to "устройство / учреждение",
            "compassion" to "сострадание / сочувствие",
            "content" to "довольный / содержание",
            "curiosity" to "любопытство",
            "remarkable" to "замечательный / выдающийся",
            "disappointment" to "разочарование",
            "eclipses" to "затмевает",
            "predominates" to "преобладает",
            "abhorrent" to "отвратительный / чуждый",
            "nomadic" to "кочевой",
            "primitive" to "первобытный / простой"
        )
        return ruMap[word] ?: "Перевод для: '$text'"
    }
}
