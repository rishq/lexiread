package com.lexiread.data.remote.openlibrary

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Open Library — catalog, metadata and cover art.
 * Docs: https://openlibrary.org/developers/api
 *
 * Used for discovery and covers only. It exposes no public-domain full text
 * endpoint we could legally stream, so nothing is ever downloaded from here.
 */
interface OpenLibraryApi {

    @GET("search.json")
    suspend fun searchBooks(
        @Query("q") query: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = DEFAULT_LIMIT,
        @Query("fields") fields: String = SEARCH_FIELDS
    ): OpenLibrarySearchResponseDto

    @GET("works/{workId}.json")
    suspend fun getWork(@Path("workId") workId: String): OpenLibraryWorkDto

    companion object {
        const val DEFAULT_LIMIT = 20

        /**
         * Asking for a narrow field list keeps the response small: the default
         * projection returns every field Open Library has, which for a 20-doc
         * page is easily 200 KB of JSON the UI never reads.
         */
        const val SEARCH_FIELDS = "key,title,author_name,first_publish_year,cover_i," +
            "isbn,language,subject,id_gutenberg,number_of_pages_median"
    }
}
