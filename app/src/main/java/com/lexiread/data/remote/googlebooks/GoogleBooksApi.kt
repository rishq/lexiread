package com.lexiread.data.remote.googlebooks

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Google Books — metadata only.
 * Docs: https://developers.google.com/books/docs/v1/using
 *
 * Deliberately restricted to search and volume metadata. Google Books serves a
 * lot of in-copyright material and protects it with DRM; this client never
 * requests `epub.downloadLink` or `pdf.downloadLink`, and no code path here
 * stores or follows one. Free full text is read through Gutendex instead.
 */
interface GoogleBooksApi {

    @GET("books/v1/volumes")
    suspend fun searchVolumes(
        @Query("q") query: String,
        @Query("startIndex") startIndex: Int = 0,
        @Query("maxResults") maxResults: Int = DEFAULT_MAX_RESULTS,
        /** Optional. Retrofit omits the parameter entirely when it is null. */
        @Query("key") apiKey: String? = null
    ): GoogleBooksSearchResponseDto

    @GET("books/v1/volumes/{volumeId}")
    suspend fun getVolume(
        @Path("volumeId") volumeId: String,
        @Query("key") apiKey: String? = null
    ): GoogleBooksVolumeDto

    companion object {
        const val DEFAULT_MAX_RESULTS = 20
        const val MAX_MAX_RESULTS = 40
    }
}
