package com.lexiread.data.remote.gutendex

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Gutendex — a JSON mirror of Project Gutenberg.
 * Docs: https://gutendex.com/
 *
 * Keyless and public-domain only, which is why it is the primary reading
 * source: every book it returns may legally be downloaded in full.
 */
interface GutendexApi {

    /**
     * The trailing slash matters: Gutendex answers `/books` with a 301 to
     * `/books/`, and every uncached request would cost an extra round-trip.
     */
    @GET("books/")
    suspend fun searchBooks(
        @Query("search") query: String,
        @Query("page") page: Int = 1,
        /** "popular", "ascending" or "descending". Ignored by the server if unsupported. */
        @Query("sort") sort: String? = null,
        @Query("languages") languages: String? = null
    ): GutendexSearchResponseDto

    @GET("books/{id}")
    suspend fun getBookById(@Path("id") id: Int): GutendexBookDto

    /** Absolute URL taken from the `formats` map of a book, never built by hand. */
    @GET
    suspend fun downloadFile(@Url url: String): ResponseBody
}
