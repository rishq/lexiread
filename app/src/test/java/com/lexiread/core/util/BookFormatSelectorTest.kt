package com.lexiread.core.util

import com.lexiread.domain.model.FormatKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole point of these tests: no URL may ever be produced that the API did
 * not send. The old implementation hardcoded
 * `gutenberg.org/files/$id/$id-0.txt`, which 404s for any book published
 * without a plain-text edition.
 */
class BookFormatSelectorTest {

    private val gutendexFormats = mapOf(
        "text/html; charset=utf-8" to "http://www.gutenberg.org/files/1342/1342-h/1342-h.htm",
        "application/epub+zip" to "https://www.gutenberg.org/ebooks/1342.epub.images",
        "text/plain; charset=us-ascii" to "https://www.gutenberg.org/files/1342/1342-0.txt",
        "image/jpeg" to "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
        "application/rdf+xml" to "https://www.gutenberg.org/ebooks/1342.rdf",
        "application/x-mobipocket-ebook" to "https://www.gutenberg.org/ebooks/1342.kindle.images",
        "application/zip" to "https://www.gutenberg.org/ebooks/1342.zip"
    )

    @Test
    fun `orders epub ahead of html ahead of text`() {
        val result = BookFormatSelector.fromGutendexFormats(gutendexFormats)

        assertEquals(
            listOf(FormatKind.EPUB, FormatKind.HTML, FormatKind.TXT),
            result.map { it.kind }
        )
    }

    @Test
    fun `drops covers metadata mobi and zipped html`() {
        val result = BookFormatSelector.fromGutendexFormats(gutendexFormats)

        assertEquals(
            setOf(FormatKind.EPUB, FormatKind.HTML, FormatKind.TXT),
            result.map { it.kind }.toSet()
        )
    }

    @Test
    fun `rewrites http urls to https because cleartext is blocked`() {
        val result = BookFormatSelector.fromGutendexFormats(gutendexFormats)

        assertTrue(result.all { it.url.startsWith("https://") })
    }

    @Test
    fun `falls back to html when no epub is published`() {
        val result = BookFormatSelector.fromGutendexFormats(gutendexFormats - "application/epub+zip")

        assertEquals(FormatKind.HTML, BookFormatSelector.pickBest(result)?.kind)
    }

    @Test
    fun `falls back to text when only text is published`() {
        val result = BookFormatSelector.fromGutendexFormats(
            mapOf("text/plain; charset=iso-8859-1" to "https://www.gutenberg.org/files/1/1-0.txt")
        )

        assertEquals(FormatKind.TXT, BookFormatSelector.pickBest(result)?.kind)
    }

    @Test
    fun `returns nothing readable when the api lists no formats`() {
        assertTrue(BookFormatSelector.fromGutendexFormats(null).isEmpty())
        assertTrue(BookFormatSelector.fromGutendexFormats(emptyMap()).isEmpty())
        assertNull(BookFormatSelector.pickBest(BookFormatSelector.fromGutendexFormats(emptyMap())))
    }

    @Test
    fun `keeps exactly one candidate per readable kind`() {
        val duplicated = gutendexFormats + ("text/html" to "https://www.gutenberg.org/other.htm")

        val html = BookFormatSelector.fromGutendexFormats(duplicated)
            .filter { it.kind == FormatKind.HTML }

        assertEquals(1, html.size)
    }

    @Test
    fun `classifies mime types with parameters`() {
        assertEquals(FormatKind.TXT, BookFormatSelector.classify("text/plain; charset=utf-8"))
        assertEquals(FormatKind.HTML, BookFormatSelector.classify("TEXT/HTML"))
        assertEquals(FormatKind.EPUB, BookFormatSelector.classify("application/epub+zip"))
        assertNull(BookFormatSelector.classify("application/rdf+xml"))
        assertNull(BookFormatSelector.classify("image/jpeg"))
    }

    @Test
    fun `extracts the cover url from the formats map`() {
        assertEquals(
            "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
            BookFormatSelector.coverUrl(gutendexFormats)
        )
    }
}
