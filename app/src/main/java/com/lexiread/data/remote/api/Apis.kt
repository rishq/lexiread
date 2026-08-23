package com.lexiread.data.remote.api

import com.lexiread.data.remote.dto.ClaudeMessagesRequestDto
import com.lexiread.data.remote.dto.ClaudeMessagesResponseDto
import com.lexiread.data.remote.dto.DictionaryDto
import com.lexiread.data.remote.dto.GeminiRequestDto
import com.lexiread.data.remote.dto.GeminiResponseDto
import com.lexiread.data.remote.dto.GutendexBookDto
import com.lexiread.data.remote.dto.GutendexResponse
import com.lexiread.data.remote.dto.MyMemoryResponse
import com.lexiread.data.remote.dto.OpenAiChatRequestDto
import com.lexiread.data.remote.dto.OpenAiChatResponseDto
import com.lexiread.data.remote.dto.OpenLibrarySearchResponse
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface GutendexApi {
    @GET("books")
    suspend fun searchBooks(@Query("search") query: String): GutendexResponse

    @GET("books/{id}")
    suspend fun getBookById(@Path("id") id: Int): GutendexBookDto

    @GET
    suspend fun downloadTextContent(@Url url: String): ResponseBody
}

interface OpenLibraryApi {
    @GET("search.json")
    suspend fun searchBooks(
        @Query("q") query: String,
        @Query("limit") limit: Int = 20,
        @Query("fields") fields: String = "key,title,author_name,cover_i,first_publish_year,ia"
    ): OpenLibrarySearchResponse
}

/** Standard Ebooks exposes an OPDS (Atom XML) catalog. */
interface StandardEbooksApi {
    @GET
    suspend fun searchOpds(@Url url: String): ResponseBody

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
