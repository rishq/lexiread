package com.lexiread.core.util

import com.lexiread.domain.model.Author
import com.lexiread.domain.model.BookFormat
import com.lexiread.domain.model.BookIdentifiers
import com.lexiread.domain.model.CatalogBook
import com.lexiread.domain.model.FormatKind
import com.lexiread.domain.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogDeduperTest {

    private fun book(
        id: String,
        title: String = "Pride and Prejudice",
        author: String = "Jane Austen",
        source: SourceKind = SourceKind.GUTENDEX,
        identifiers: BookIdentifiers = BookIdentifiers(),
        formats: List<BookFormat> = emptyList(),
        description: String? = null,
        coverUrl: String? = null,
        publicDomain: Boolean = false
    ) = CatalogBook(
        id = id,
        title = title,
        authors = listOf(Author(author)),
        coverUrl = coverUrl,
        description = description,
        subjects = emptyList(),
        source = source,
        formats = formats,
        identifiers = identifiers,
        isPublicDomain = publicDomain
    )

    private val epub = BookFormat(FormatKind.EPUB, "application/epub+zip", "https://gutenberg.org/1342.epub")

    @Test
    fun `merges an open library record onto its gutenberg edition`() {
        val fromOpenLibrary = book(
            id = "ol_OL27448W",
            source = SourceKind.OPEN_LIBRARY,
            identifiers = BookIdentifiers(openLibraryWorkId = "OL27448W", gutenbergId = 1342),
            description = "A novel of manners."
        )
        val fromGutenberg = book(
            id = "gutenberg_1342",
            identifiers = BookIdentifiers(gutenbergId = 1342),
            formats = listOf(epub),
            publicDomain = true
        )

        val merged = CatalogDeduper.dedupe(listOf(fromOpenLibrary, fromGutenberg))

        assertEquals(1, merged.size)
        assertTrue(merged[0].canRead)
        assertEquals("A novel of manners.", merged[0].description)
        assertEquals("OL27448W", merged[0].identifiers.openLibraryWorkId)
    }

    @Test
    fun `matches two catalogues on isbn`() {
        val google = book(
            id = "gb_abc",
            source = SourceKind.GOOGLE_BOOKS,
            identifiers = BookIdentifiers(googleBooksId = "abc", isbn13 = "9780141439518")
        )
        val ol = book(
            id = "ol_OL1W",
            source = SourceKind.OPEN_LIBRARY,
            identifiers = BookIdentifiers(openLibraryWorkId = "OL1W", isbn13 = "9780141439518")
        )

        assertEquals(1, CatalogDeduper.dedupe(listOf(google, ol)).size)
    }

    @Test
    fun `matches on normalized title and author when no identifier is shared`() {
        val first = book(id = "ol_A", title = "Pride and Prejudice", author = "Jane Austen")
        val second = book(
            id = "gb_B",
            title = "  pride   and prejudice!  ",
            author = "Austen, Jane",
            source = SourceKind.GOOGLE_BOOKS
        )

        assertEquals(1, CatalogDeduper.dedupe(listOf(first, second)).size)
    }

    @Test
    fun `keeps different books apart`() {
        val first = book(id = "ol_A", title = "Pride and Prejudice")
        val second = book(id = "ol_B", title = "Emma")

        assertEquals(2, CatalogDeduper.dedupe(listOf(first, second)).size)
    }

    @Test
    fun `backfills a missing cover from the other catalogue`() {
        val withoutCover = book(id = "gutenberg_1", formats = listOf(epub), publicDomain = true)
        val withCover = book(
            id = "ol_1",
            source = SourceKind.OPEN_LIBRARY,
            coverUrl = "https://covers.openlibrary.org/b/id/1-M.jpg"
        )

        val merged = CatalogDeduper.dedupe(listOf(withoutCover, withCover))

        assertEquals(1, merged.size)
        assertEquals("https://covers.openlibrary.org/b/id/1-M.jpg", merged[0].coverUrl)
        assertTrue(merged[0].canRead)
    }

    @Test
    fun `author normalization ignores word order and punctuation`() {
        assertEquals(
            CatalogDeduper.normalizeAuthor("Austen, Jane"),
            CatalogDeduper.normalizeAuthor("Jane Austen")
        )
        assertEquals(
            CatalogDeduper.normalizeAuthor("Sir Arthur Conan Doyle"),
            CatalogDeduper.normalizeAuthor("Doyle, Arthur Conan")
        )
    }

    @Test
    fun `never loses a readable edition when merging`() {
        val readable = book(id = "gutenberg_1", formats = listOf(epub), publicDomain = true)
        val richer = book(
            id = "gb_1",
            source = SourceKind.GOOGLE_BOOKS,
            description = "x".repeat(500),
            coverUrl = "https://cover"
        )

        val merged = CatalogDeduper.dedupe(listOf(richer, readable))

        assertTrue(merged[0].canRead)
        assertEquals(1, merged[0].formats.size)
    }

    @Test
    fun `preserves insertion order`() {
        val first = book(id = "ol_A", title = "Anna Karenina")
        val second = book(id = "ol_B", title = "War and Peace")

        val result = CatalogDeduper.dedupe(listOf(first, second))

        assertEquals("Anna Karenina", result[0].title)
        assertFalse(result[1].canRead)
    }
}
