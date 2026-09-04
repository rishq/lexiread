package com.lexiread.data.remote.api

import com.lexiread.data.remote.dto.ClaudeMessagesRequestDto
import com.lexiread.data.remote.dto.ClaudeMessagesResponseDto
import com.lexiread.data.remote.dto.DictionaryDto
import com.lexiread.data.remote.dto.GeminiRequestDto
import com.lexiread.data.remote.dto.GeminiResponseDto
import com.lexiread.data.remote.dto.InternetArchiveMetadataResponse
import com.lexiread.data.remote.dto.InternetArchiveSearchResponse
import com.lexiread.data.remote.dto.MyMemoryResponse
import com.lexiread.data.remote.dto.OpenAiChatRequestDto
import com.lexiread.data.remote.dto.OpenAiChatResponseDto
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Gutendex lives in `data.remote.gutendex` together with its DTOs; every
 * catalogue now owns a self-contained package.
 */

/** Standard Ebooks exposes an OPDS (Atom XML) catalog. */
interface StandardEbooksApi {
    @GET
    suspend fun searchOpds(@Url url: String): ResponseBody

    @GET
    suspend fun downloadFile(@Url url: String): ResponseBody
}

/**
 * Project Gutenberg Australia publishes a single plain-text index
 * (`gutindex_aus.txt`) plus per-book `.txt` / `.html` files. There is no
 * structured API, so one generic `@Url` fetcher covers the index and the
 * individual book files alike.
 */
interface PgaApi {
    @GET
    suspend fun fetch(@Url url: String): ResponseBody
}

/**
 * A Mylib-style catalogue: plain HTML, no API. One generic `@Url` fetcher covers
 * the search pages and the book files alike; parsing is done by Jsoup in
 * `MyLibBookSource`, so this interface stays trivial.
 */
interface MyLibApi {
    @GET
    suspend fun fetch(@Url url: String): ResponseBody
}

/**
 * Public Internet Archive endpoints. The source searches for records that
 * advertise EPUB and checks metadata before it downloads a concrete file.
 */
interface InternetArchiveApi {
    @GET("advancedsearch.php")
    suspend fun searchBooks(
        @Query("q") query: String,
        @Query("fl[]") fields: String = "identifier,title,creator,year",
        @Query("rows") rows: Int = 20,
        @Query("page") page: Int = 1,
        @Query("output") output: String = "json"
    ): InternetArchiveSearchResponse

    @GET("metadata/{identifier}")
    suspend fun getMetadata(@Path("identifier") identifier: String): InternetArchiveMetadataResponse

    @GET
    suspend fun downloadFile(@Url url: String): ResponseBody
}

interface DictionaryApi {
    @GET("api/v2/entries/en/{word}")
    suspend fun getDefinition(@Path("word") word: String): List<DictionaryDto>
}

interface TranslationApi {
    @GET("get")
    suspend fun translate(
        @Query("q") text: String,
        @Query("langpair") langpair: String = "en|ru"
    ): MyMemoryResponse
}

interface GeminiApi {
    @POST("v1beta/models/gemini-2.5-flash:generateContent")
    suspend fun generateContent(
        @Header("x-goog-api-key") apiKey: String,
        @Body request: GeminiRequestDto
    ): GeminiResponseDto
}

/** Used for both OpenAI (api.openai.com) and DeepSeek (api.deepseek.com). */
interface OpenAiChatApi {
    @POST("v1/chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authHeader: String,
        @Body request: OpenAiChatRequestDto
    ): OpenAiChatResponseDto
}

interface ClaudeApi {
    @POST("v1/messages")
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: ClaudeMessagesRequestDto
    ): ClaudeMessagesResponseDto
}
