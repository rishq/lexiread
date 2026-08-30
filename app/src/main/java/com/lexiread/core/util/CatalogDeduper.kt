package com.lexiread.core.util

import com.lexiread.domain.model.BookIdentifiers
import com.lexiread.domain.model.CatalogBook

/**
 * Merges the same edition coming from several catalogues into one card.
 *
 * Strategy: try the strong identifiers first (ISBN-13, ISBN-10, Open Library
 * work key, Gutenberg id) and fall back to a normalized "title + author" pair
 * for records that carry no shared identifier at all — which is the common case
 * for public-domain works indexed before ISBN existed.
 */
object CatalogDeduper {

    fun dedupe(books: List<CatalogBook>): List<CatalogBook> {
        // A cluster owns every key its members have ever advertised, not just
        // the strongest one. Storing a book under a single key would miss
        // matches: a Gutenberg record keyed only by its Gutenberg id never
        // meets an Open Library record keyed only by its work key, even though
        // both carry the same id.
        val clusterKeys = ArrayList<MutableSet<String>>()
        val clusterBooks = ArrayList<CatalogBook>()

        for (book in books) {
            val keys = keysFor(book).toSet()
            val index = clusterKeys.indexOfFirst { cluster -> cluster.any { it in keys } }
            if (index < 0) {
                clusterKeys += keys.toMutableSet()
                clusterBooks += book
            } else {
                val merged = merge(clusterBooks[index], book)
                clusterBooks[index] = merged
                clusterKeys[index] += keysFor(merged)
            }
        }
        return clusterBooks
    }

    /** Ordered strongest-first. The first key is also the storage key. */
    fun keysFor(book: CatalogBook): List<String> = buildList {
        book.identifiers.isbn13?.let { add("isbn:$it") }
        book.identifiers.isbn10?.let { add("isbn:$it") }
        book.identifiers.openLibraryWorkId?.let { add("ol:$it") }
        book.identifiers.gutenbergId?.let { add("gut:$it") }
        add("ta:${normalizeTitle(book.title)}|${normalizeAuthor(book.authorLine)}")
    }

    fun normalizeTitle(title: String): String =
        title.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /**
     * Author strings differ wildly between catalogues ("Doyle, Arthur Conan",
     * "Arthur Conan Doyle", "Sir Arthur Conan Doyle"). Dropping honorifics and
     * sorting the surviving tokens makes the comparison both title- and
     * order-independent.
     */
    fun normalizeAuthor(author: String): String =
        author.lowercase()
            .replace(Regex("[^a-z0-9 ]+"), "")
            .split(Regex("\\s+"))
            .filter { it.length > 1 && it !in HONORIFICS }
            .sorted()
            .joinToString(" ")

    /**
     * Keeps the record that can actually be read, then backfills whatever
     * metadata the other catalogue knew and this one did not.
     */
    fun merge(first: CatalogBook, second: CatalogBook): CatalogBook {
        val base = when {
            second.canRead && !first.canRead -> second
            first.canRead && !second.canRead -> first
            else -> if (completeness(second) > completeness(first)) second else first
        }
        val other = if (base === first) second else first

        return base.copy(
            title = base.title.ifBlank { other.title },
            authors = base.authors.ifEmpty { other.authors },
            coverUrl = base.coverUrl ?: other.coverUrl,
            description = longest(base.description, other.description),
            language = base.language ?: other.language,
            subjects = (base.subjects + other.subjects).distinct(),
            publishedYear = base.publishedYear ?: other.publishedYear,
            downloadCount = base.downloadCount ?: other.downloadCount,
            formats = base.formats.ifEmpty { other.formats },
            isPublicDomain = base.isPublicDomain || other.isPublicDomain,
            identifiers = mergeIdentifiers(base.identifiers, other.identifiers)
        )
    }

    private fun mergeIdentifiers(base: BookIdentifiers, other: BookIdentifiers) = BookIdentifiers(
        gutenbergId = base.gutenbergId ?: other.gutenbergId,
        openLibraryWorkId = base.openLibraryWorkId ?: other.openLibraryWorkId,
        googleBooksId = base.googleBooksId ?: other.googleBooksId,
        isbn13 = base.isbn13 ?: other.isbn13,
        isbn10 = base.isbn10 ?: other.isbn10
    )

    private fun longest(first: String?, second: String?): String? =
        when {
            first == null -> second
            second == null -> first
            else -> if (second.length > first.length) second else first
        }

    private fun completeness(book: CatalogBook): Int =
        (book.description?.length ?: 0) +
            book.subjects.size * 10 +
            (if (book.coverUrl != null) 50 else 0) +
            (if (book.publishedYear != null) 20 else 0)

    /**
     * Titles and styles one catalogue adds and another omits. They carry no
     * identifying information, so keeping them would only split one author
     * into two.
     */
    private val HONORIFICS = setOf(
        "sir", "dame", "lord", "lady", "dr", "mr", "mrs", "ms", "prof",
        "rev", "hon", "capt", "col", "lt", "gen", "adm"
    )
}
