package com.lexiread.data.remote.gutendex

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GutendexSearchResponseDto(
    val count: Int? = null,
    val next: String? = null,
    val previous: String? = null,
    val results: List<GutendexBookDto>? = null
)

@JsonClass(generateAdapter = true)
data class GutendexPersonDto(
    val name: String? = null,
    val birth_year: Int? = null,
    val death_year: Int? = null
)

@JsonClass(generateAdapter = true)
data class GutendexBookDto(
    val id: Int,
    val title: String,
    val authors: List<GutendexPersonDto>? = null,
    val translators: List<GutendexPersonDto>? = null,
    /** Real blurbs when Gutendex has them — far better than joining subjects. */
    val summaries: List<String>? = null,
    val subjects: List<String>? = null,
    val bookshelves: List<String>? = null,
    val languages: List<String>? = null,
    /** false means the work is in the public domain in its country of origin. */
    val copyright: Boolean? = null,
    val media_type: String? = null,
    /**
     * MIME type -> absolute URL, e.g.
     * "application/epub+zip" -> "https://www.gutenberg.org/ebooks/1342.epub.images"
     */
    val formats: Map<String, String>? = null,
    val download_count: Int? = null
)
