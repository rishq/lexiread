package com.lexiread.domain.repository

import com.lexiread.domain.model.Book

/**
 * A pluggable online book catalog. Implementations provide search and,
 * when the source allows it, content download.
 */
interface BookSource {
    /** Unique prefix used in [Book.id], e.g. "gutenberg". */
    val idPrefix: String

    /** Human-readable source name for UI. */
    val displayName: String

    suspend fun search(query: String): List<Book>

    /**
     * Whether [downloadContent] can supply readable content for this book.
     */
    fun canDownload(book: Book): Boolean

    /**
     * Downloads content and returns a copy of [book] with either
     * [Book.fullText] (plain-text sources) or [Book.filePath] + [Book.format]
     * (file-based sources like EPUB) populated. Throws on failure.
     */
    suspend fun downloadContent(book: Book): Book

    fun owns(book: Book): Boolean = book.id.startsWith("${idPrefix}_")
}
