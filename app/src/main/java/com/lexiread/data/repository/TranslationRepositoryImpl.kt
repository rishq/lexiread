package com.lexiread.data.repository

import com.lexiread.data.local.dao.CacheDao
import com.lexiread.data.local.entity.TranslationCacheEntity
import com.lexiread.data.remote.api.TranslationApi
import com.lexiread.domain.model.TranslationResult
import com.lexiread.domain.repository.TranslationRepository

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

        // 2. Fetch from MyMemory API
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
        }
    }
}
