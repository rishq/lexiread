package com.example.data.remote.dto

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

// --- OpenLibrary DTOs ---
@JsonClass(generateAdapter = true)
data class OpenLibrarySearchResponse(
    val numFound: Int?,
    val docs: List<OpenLibraryDocDto>?
)

@JsonClass(generateAdapter = true)
data class OpenLibraryDocDto(
    val key: String?,
    val title: String,
    val author_name: List<String>?,
    val first_publish_year: Int?,
    val cover_i: Int?,
    val isbn: List<String>?,
    val subject: List<String>?
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

@JsonClass(generateAdapter = true)
data class GeminiCandidateDto(
    val content: GeminiContentDto?
)
