package com.lexiread.core.util

import com.lexiread.domain.model.BookFormat
import com.lexiread.domain.model.FormatKind

/**
 * Chooses the file a book should be read from.
 *
 * The whole point of this class is that URLs are never built from a template.
 * Every candidate comes from the `formats` map that Gutendex returns, and the
 * decision is made on the declared MIME type. A missing or renamed file on the
 * Gutenberg side therefore degrades to the next format instead of 404-ing.
 */
object BookFormatSelector {

    private const val MIME_EPUB = "application/epub+zip"
    private const val MIME_XHTML = "application/xhtml+xml"
    private const val MIME_HTML = "text/html"
    private const val MIME_PLAIN = "text/plain"
    private const val MIME_COVER = "image/jpeg"

    /** Best reading experience first. */
    private val READING_PREFERENCE = listOf(FormatKind.EPUB, FormatKind.HTML, FormatKind.TXT)

    /**
     * Converts a raw Gutendex `formats` map into an ordered list, one entry per
     * readable kind, best first. Unreadable kinds (RDF metadata, MOBI, zipped
     * HTML we cannot extract) and the cover image are dropped.
     */
    fun fromGutendexFormats(formats: Map<String, String>?): List<BookFormat> {
        if (formats.isNullOrEmpty()) return emptyList()

        return formats.asSequence()
            .mapNotNull { (mime, url) ->
                val kind = classify(mime) ?: return@mapNotNull null
                BookFormat(kind = kind, mimeType = mime.trim(), url = url.forceHttps())
            }
            .filter { it.url.isNotBlank() }
            .groupBy { it.kind }
            .map { (_, candidates) -> candidates.first() }
            .sortedBy { READING_PREFERENCE.indexOf(it.kind).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
            .toList()
    }

    /**
     * Picks the best readable format according to [READING_PREFERENCE].
     * Returns null when nothing readable is available.
     */
    fun pickBest(formats: List<BookFormat>): BookFormat? =
        READING_PREFERENCE.firstNotNullOfOrNull { kind -> formats.firstOrNull { it.kind == kind } }

    /**
     * Maps a MIME type (with or without parameters) onto a [FormatKind].
     * Returns null for anything the built-in reader cannot open.
     */
    fun classify(rawMime: String): FormatKind? {
        val mime = rawMime.substringBefore(';').trim().lowercase()
        return when (mime) {
            MIME_EPUB -> FormatKind.EPUB
            MIME_HTML, MIME_XHTML -> FormatKind.HTML
            MIME_PLAIN -> FormatKind.TXT
            // Cover art, RDF metadata, MOBI, zipped HTML: not readable here.
            else -> null
        }
    }

    /** The cover image, or null when the record has none. */
    fun coverUrl(formats: Map<String, String>?): String? =
        formats?.get(MIME_COVER)?.takeIf { it.isNotBlank() }?.forceHttps()
}

/**
 * Gutenberg and Google Books still serve http URLs. The manifest sets
 * `cleartextTrafficPermitted="false"`, so an http link would be blocked by the
 * platform and fail at runtime.
 */
fun String.forceHttps(): String =
    if (startsWith("http://", ignoreCase = true)) "https://" + substring("http://".length) else this
