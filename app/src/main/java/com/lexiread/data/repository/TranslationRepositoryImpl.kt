package com.lexiread.data.repository

import com.lexiread.data.local.dao.CacheDao
import com.lexiread.data.local.entity.TranslationCacheEntity
import com.lexiread.data.remote.api.ClaudeApi
import com.lexiread.data.remote.api.GeminiApi
import com.lexiread.data.remote.api.OpenAiChatApi
import com.lexiread.data.remote.dto.ClaudeMessageDto
import com.lexiread.data.remote.dto.ClaudeMessagesRequestDto
import com.lexiread.data.remote.dto.GeminiContentDto
import com.lexiread.data.remote.dto.GeminiGenerationConfigDto
import com.lexiread.data.remote.dto.GeminiPartDto
import com.lexiread.data.remote.dto.GeminiRequestDto
import com.lexiread.data.remote.dto.OpenAiChatRequestDto
import com.lexiread.data.remote.dto.OpenAiMessageDto
import com.lexiread.data.remote.api.TranslationApi
import com.lexiread.domain.model.TranslationResult
import com.lexiread.domain.repository.TranslationRepository

/**
 * Translates text using the user's configured AI provider for high quality,
 * falling back to the free MyMemory API when no AI key is set or the AI call fails.
 */
class TranslationRepositoryImpl(
    private val translationApi: TranslationApi,
    private val cacheDao: CacheDao,
    private val geminiApi: GeminiApi,
    private val openAiApi: OpenAiChatApi,
    private val claudeApi: ClaudeApi,
    private val deepSeekApi: OpenAiChatApi,
    private val providerConfigProvider: suspend () -> Pair<String, String>
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
        if (cached != null && cached.timestamp >= System.currentTimeMillis() - CACHE_TTL_MS) {
            return Result.success(
                TranslationResult(
                    sourceText = cleanText,
                    translatedText = cached.translatedText,
                    sourceLang = "en",
                    targetLang = targetLang
                )
            )
        }

        // 2. Preferred: AI translation via the configured provider (much more accurate)
        val aiTranslated = try {
            translateWithAi(cleanText, targetLang)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (!aiTranslated.isNullOrBlank()) {
            return Result.success(cacheAndReturn(cacheKey, cleanText, aiTranslated.trim(), targetLang))
        }

        // 3. Fallback: MyMemory API
        return try {
            val response = translationApi.translate(cleanText, "en|$targetLang")
            val translated = response.responseData?.translatedText

            if (translated.isNullOrBlank() || translated.contains("QUERY LENGTH LIMIT EXCEEDED")) {
                throw Exception("Translation API limit reached")
            }

            Result.success(cacheAndReturn(cacheKey, cleanText, translated, targetLang))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun cacheAndReturn(
        cacheKey: String,
        source: String,
        translated: String,
        targetLang: String
    ): TranslationResult {
        cacheDao.insertTranslationCache(
            TranslationCacheEntity(
                cacheKey = cacheKey,
                sourceText = source,
                translatedText = translated,
                targetLang = targetLang,
                timestamp = System.currentTimeMillis()
            )
        )
        return TranslationResult(
            sourceText = source,
            translatedText = translated,
            sourceLang = "en",
            targetLang = targetLang
        )
    }

    private suspend fun translateWithAi(text: String, targetLang: String): String? {
        val (provider, apiKey) = providerConfigProvider()
        if (apiKey.isBlank()) return null

        val langName = languageDisplayName(targetLang)
        val prompt =
            "Translate the following English text into $langName. " +
                "Respond ONLY with the translation itself — no quotes, no explanations, no transliteration.\n\n$text"

        return when (provider) {
            AiProviders.CHATGPT -> requestOpenAi(openAiApi, "gpt-4o-mini", apiKey, prompt)
            AiProviders.DEEPSEEK -> requestOpenAi(deepSeekApi, "deepseek-chat", apiKey, prompt)
            AiProviders.CLAUDE -> requestClaude(apiKey, prompt)
            else -> requestGemini(apiKey, prompt)
        }?.trim()?.removeSurrounding("\"")
    }

    private suspend fun requestGemini(apiKey: String, prompt: String): String? {
        val request = GeminiRequestDto(
            contents = listOf(GeminiContentDto(parts = listOf(GeminiPartDto(text = prompt)))),
            generationConfig = GeminiGenerationConfigDto(temperature = 0.1f)
        )
        return geminiApi.generateContent(apiKey, request)
            .candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
    }

    private suspend fun requestOpenAi(
        api: OpenAiChatApi,
        model: String,
        apiKey: String,
        prompt: String
    ): String? {
        val request = OpenAiChatRequestDto(
            model = model,
            messages = listOf(OpenAiMessageDto(role = "user", content = prompt)),
            temperature = 0.1
        )
        return api.chatCompletions("Bearer $apiKey", request)
            .choices?.firstOrNull()?.message?.content
    }

    private suspend fun requestClaude(apiKey: String, prompt: String): String? {
        val request = ClaudeMessagesRequestDto(
            model = "claude-3-5-haiku-20241022",
            maxTokens = 1024,
            messages = listOf(ClaudeMessageDto(role = "user", content = prompt))
        )
        return claudeApi.createMessage(apiKey, "2023-06-01", request)
            .content?.firstOrNull { it?.type == "text" }?.text
    }

    private fun languageDisplayName(code: String): String = when (code.lowercase()) {
        "ru" -> "Russian"
        "es" -> "Spanish"
        "de" -> "German"
        "fr" -> "French"
        else -> code.uppercase()
    }

    private companion object {
        const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
