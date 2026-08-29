package com.lexiread.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// --- Gutendex DTOs ---
@JsonClass(generateAdapter = true)
data class GutendexResponse(
    val count: Int?,
    val next: String?,
    val previous: String?,
    val results: List<GutendexBookDto>?
)

@JsonClass(generateAdapter = true)
data class GutendexPersonDto(
    val name: String?,
    val birth_year: Int?,
    val death_year: Int?
)

@JsonClass(generateAdapter = true)
data class GutendexBookDto(
    val id: Int,
    val title: String,
    val authors: List<GutendexPersonDto>?,
    val translators: List<GutendexPersonDto>?,
    val subjects: List<String>?,
    val bookshelves: List<String>?,
    val languages: List<String>?,
    val copyright: Boolean?,
    val media_type: String?,
    val formats: Map<String, String>?,
    val download_count: Int?
)

// --- Internet Archive DTOs ---
@JsonClass(generateAdapter = true)
data class InternetArchiveSearchResponse(
    val response: InternetArchiveSearchResult? = null
)

@JsonClass(generateAdapter = true)
data class InternetArchiveSearchResult(
    val numFound: Int? = null,
    val docs: List<InternetArchiveSearchDocDto>? = null
)

@JsonClass(generateAdapter = true)
data class InternetArchiveSearchDocDto(
    val identifier: String? = null,
    val title: String? = null,
    // Archive returns this field as either a string or a list, depending on
    // the uploaded item. Moshi's Any adapter preserves both representations.
    val creator: Any? = null,
    val year: Any? = null
)

@JsonClass(generateAdapter = true)
data class InternetArchiveMetadataResponse(
    val metadata: InternetArchiveItemMetadataDto? = null,
    val files: List<InternetArchiveFileDto>? = null
)

@JsonClass(generateAdapter = true)
data class InternetArchiveItemMetadataDto(
    @Json(name = "access-restricted") val accessRestricted: String? = null
)

@JsonClass(generateAdapter = true)
data class InternetArchiveFileDto(
    val name: String? = null,
    val format: String? = null,
    val private: String? = null
)

// --- Free Dictionary API DTOs ---
@JsonClass(generateAdapter = true)
data class DictionaryDto(
    val word: String?,
    val phonetic: String?,
    val phonetics: List<PhoneticDto>?,
    val meanings: List<MeaningDto>?
)

@JsonClass(generateAdapter = true)
data class PhoneticDto(
    val text: String?,
    val audio: String?
)

@JsonClass(generateAdapter = true)
data class MeaningDto(
    val partOfSpeech: String?,
    val definitions: List<DefinitionDto>?,
    val synonyms: List<String>?,
    val antonyms: List<String>?
)

@JsonClass(generateAdapter = true)
data class DefinitionDto(
    val definition: String?,
    val example: String?,
    val synonyms: List<String>?,
    val antonyms: List<String>?
)

// --- MyMemory Translation DTOs ---
@JsonClass(generateAdapter = true)
data class MyMemoryResponse(
    val responseData: MyMemoryData?,
    val responseStatus: Int?
)

@JsonClass(generateAdapter = true)
data class MyMemoryData(
    val translatedText: String?,
    val match: Double?
)

// --- Gemini REST API DTOs ---
@JsonClass(generateAdapter = true)
data class GeminiRequestDto(
    val contents: List<GeminiContentDto>,
    val generationConfig: GeminiGenerationConfigDto? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContentDto(
    val parts: List<GeminiPartDto>
)

@JsonClass(generateAdapter = true)
data class GeminiPartDto(
    val text: String?
)

@JsonClass(generateAdapter = true)
data class GeminiGenerationConfigDto(
    val temperature: Float? = null,
    val responseMimeType: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiResponseDto(
    val candidates: List<GeminiCandidateDto>?
)

// --- OpenAI-compatible Chat Completions DTOs (OpenAI / DeepSeek) ---
@JsonClass(generateAdapter = true)
data class OpenAiChatRequestDto(
    val model: String,
    val messages: List<OpenAiMessageDto>,
    val temperature: Double? = null,
    @Json(name = "response_format") val responseFormat: OpenAiResponseFormatDto? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiMessageDto(
    val role: String,
    val content: String
)

@JsonClass(generateAdapter = true)
data class OpenAiResponseFormatDto(
    val type: String
)

@JsonClass(generateAdapter = true)
data class OpenAiChatResponseDto(
    val choices: List<OpenAiChoiceDto>?
)

@JsonClass(generateAdapter = true)
data class OpenAiChoiceDto(
    val message: OpenAiMessageDto?
)

// --- Anthropic Claude Messages DTOs ---
@JsonClass(generateAdapter = true)
data class ClaudeMessagesRequestDto(
    val model: String,
    @Json(name = "max_tokens") val maxTokens: Int,
    val messages: List<ClaudeMessageDto>
)

@JsonClass(generateAdapter = true)
data class ClaudeMessageDto(
    val role: String,
    val content: String
)

@JsonClass(generateAdapter = true)
data class ClaudeMessagesResponseDto(
    val content: List<ClaudeContentBlockDto>?
)

@JsonClass(generateAdapter = true)
data class ClaudeContentBlockDto(
    val type: String?,
    val text: String?
)

@JsonClass(generateAdapter = true)
data class GeminiCandidateDto(
    val content: GeminiContentDto?
)
