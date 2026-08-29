package com.lexiread.data.remote.openlibrary

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OpenLibrarySearchResponseDto(
    val numFound: Int? = null,
    val start: Int? = null,
    val numFoundExact: Boolean? = null,
    val docs: List<OpenLibraryDocDto>? = null
)

@JsonClass(generateAdapter = true)
data class OpenLibraryDocDto(
    /** "/works/OL27448W" */
    val key: String? = null,
    val title: String? = null,
    val author_name: List<String>? = null,
    val first_publish_year: Int? = null,
    /** Cover id, not a URL: "https://covers.openlibrary.org/b/id/{cover_i}-M.jpg" */
    val cover_i: Int? = null,
    val isbn: List<String>? = null,
    val language: List<String>? = null,
    val subject: List<String>? = null,
    val id_gutenberg: List<String>? = null,
    val number_of_pages_median: Int? = null
)

@JsonClass(generateAdapter = true)
data class OpenLibraryWorkDto(
    val key: String? = null,
    val title: String? = null,
    /**
     * Either a plain string or {"type":"/type/text","value":"..."}.
     * Moshi's Any adapter preserves both shapes.
     */
    val description: Any? = null,
    val subjects: List<String>? = null,
    val authors: List<OpenLibraryAuthorRefDto>? = null,
    val covers: List<Int>? = null
) {
    /** Normalizes the two shapes Open Library uses for `description`. */
    fun descriptionText(): String? = when (val value = description) {
        is String -> value.trim().takeIf { it.isNotBlank() }
        is Map<*, *> -> (value["value"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        else -> null
    }
}

@JsonClass(generateAdapter = true)
data class OpenLibraryAuthorRefDto(
    val author: OpenLibraryAuthorLinkDto? = null,
    val type: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenLibraryAuthorLinkDto(
    val key: String? = null
)
