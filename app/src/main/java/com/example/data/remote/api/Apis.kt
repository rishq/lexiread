package com.example.data.remote.api

import com.example.data.remote.dto.DictionaryDto
import com.example.data.remote.dto.GeminiRequestDto
import com.example.data.remote.dto.GeminiResponseDto
import com.example.data.remote.dto.GutendexBookDto
import com.example.data.remote.dto.GutendexResponse
import com.example.data.remote.dto.MyMemoryResponse
import com.example.data.remote.dto.OpenLibrarySearchResponse
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
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
        @Query("limit") limit: Int = 20
    ): OpenLibrarySearchResponse
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
        @Query("key") apiKey: String,
        @Body request: GeminiRequestDto
    ): GeminiResponseDto
}
