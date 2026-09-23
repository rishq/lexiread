package com.lexiread.data.remote.mylib

import com.lexiread.data.source.MyLibEntry
import com.lexiread.domain.model.BookFormat
import com.lexiread.domain.model.FormatKind
import com.squareup.moshi.JsonClass

/**
 * Z-Library EAPI (`/eapi/book/search`) response.
 *
 * The HTML search pages are behind a JS browser check that plain HTTP
 * clients cannot pass, while these JSON endpoints answer anonymously, so
 * catalogue search goes through here. Field names match the EAPI keys
 * exactly; Moshi (reflection adapter) binds them without annotations.
 */
@JsonClass(generateAdapter = true)
data class MyLibEapiSearchResponse(
    val success: Int = 0,
    val books: List<MyLibEapiBook> = emptyList(),
    val pagination: MyLibEapiPagination? = null
)

@JsonClass(generateAdapter = true)
data class MyLibEapiBook(
    val id: Long = 0,
    val title: String? = null,
    val author: String? = null,
    val year: Int? = null,
    val language: String? = null,
    val cover: String? = null,
    val description: String? = null,
    val extension: String? = null,
    val dl: String? = null,
    val href: String? = null
)

@JsonClass(generateAdapter = true)
data class MyLibEapiPagination(
    val current: Int = 1,
    val total_pages: Int = 1,
    val total_items: Int? = null
)

/** Strips HTML tags from EAPI descriptions. Hoisted: used per book. */
private val TAG_REGEX = Regex("<[^>]*>")

/** EAPI extensions the reader can open, mapped to domain formats. */
private val readableExtensions = mapOf(
    "epub" to (FormatKind.EPUB to "application/epub+zip"),
    "fb2" to (FormatKind.FB2 to "application/x-fictionbook+xml"),
    "txt" to (FormatKind.TXT to "text/plain"),
    "pdf" to (FormatKind.PDF to "application/pdf"),
    "html" to (FormatKind.HTML to "text/html"),
    "htm" to (FormatKind.HTML to "text/html")
)

/**
 * Maps one EAPI record to a remembered catalogue entry. Returns null when
 * the record carries no usable title. The `dl` path is kept as the format
 * URL so a later download attempt hits the real link (and its auth/quota
 * error) instead of a dead end.
 */
internal fun MyLibEapiBook.toEntry(host: String): MyLibEntry? {
    val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val ext = extension?.lowercase()
    val format = ext?.let { readableExtensions[it] }?.let { (kind, mime) ->
        val path = dl?.takeIf { it.isNotBlank() } ?: return@let null
        BookFormat(kind = kind, mimeType = mime, url = "https://$host$path")
    } ?: return null
    val cleanCover = cover?.takeIf { it.isNotBlank() }?.let {
        if ("cover-not-exists" in it) null
        else if (it.startsWith("/")) "https://$host$it"
        else it
    }
    return MyLibEntry(
        id = id.toString(),
        title = cleanTitle,
        author = author?.split(";")?.map { it.trim() }
            ?.filter { it.isNotEmpty() }?.joinToString(", ")?.takeIf { it.isNotBlank() },
        coverUrl = cleanCover,
        detailUrl = href?.takeIf { it.isNotBlank() },
        formats = listOf(format),
        language = language?.trim()?.takeIf { it.isNotBlank() },
        year = year?.takeIf { it > 0 },
        description = description?.replace(TAG_REGEX, "")?.trim()?.takeIf { it.isNotBlank() }
    )
}
