package com.lexiread.data.remote.googlebooks

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GoogleBooksSearchResponseDto(
    val kind: String? = null,
    val totalItems: Int? = null,
    val items: List<GoogleBooksVolumeDto>? = null
)

@JsonClass(generateAdapter = true)
data class GoogleBooksVolumeDto(
    val id: String? = null,
    val volumeInfo: GoogleBooksVolumeInfoDto? = null,
    val accessInfo: GoogleBooksAccessInfoDto? = null
)

@JsonClass(generateAdapter = true)
data class GoogleBooksVolumeInfoDto(
    val title: String? = null,
    val subtitle: String? = null,
    val authors: List<String>? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val description: String? = null,
    val categories: List<String>? = null,
    val language: String? = null,
    val pageCount: Int? = null,
    val imageLinks: GoogleBooksImageLinksDto? = null,
    val industryIdentifiers: List<GoogleBooksIdentifierDto>? = null
)

@JsonClass(generateAdapter = true)
data class GoogleBooksImageLinksDto(
    val thumbnail: String? = null,
    val smallThumbnail: String? = null
)

@JsonClass(generateAdapter = true)
data class GoogleBooksIdentifierDto(
    /** "ISBN_13" or "ISBN_10" */
    val type: String? = null,
    val identifier: String? = null
)

@JsonClass(generateAdapter = true)
data class GoogleBooksAccessInfoDto(
    /** "ALL_PAGES" for freely readable volumes, "PARTIAL" or "NO_PAGES" otherwise. */
    val viewability: String? = null,
    val publicDomain: Boolean? = null,
    val epub: GoogleBooksFormatAvailabilityDto? = null,
    val pdf: GoogleBooksFormatAvailabilityDto? = null,
    val webReaderLink: String? = null
)

/**
 * Availability descriptors are parsed for display purposes only — the
 * downloadLink is never requested.
 */
@JsonClass(generateAdapter = true)
data class GoogleBooksFormatAvailabilityDto(
    val isAvailable: Boolean? = null
)
