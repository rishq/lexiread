package com.lexiread.data.source

import com.lexiread.data.remote.api.GutendexApi
import com.lexiread.data.remote.api.OpenLibraryApi
import com.lexiread.data.remote.dto.GutendexBookDto
import com.lexiread.data.remote.dto.GutendexPersonDto
import com.lexiread.data.remote.dto.GutendexResponse
import com.lexiread.data.remote.dto.OpenLibraryDocDto
import com.lexiread.data.remote.dto.OpenLibrarySearchResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourcesTest {

    private fun gutendexDto() = GutendexBookDto(
        id = 1342,
        title = "Pride and Prejudice",
        authors = listOf(GutendexPersonDto(name = "Jane Austen", birth_year = 1775, death_year = 1817)),
        translators = null,
        subjects = listOf("Love stories"),
        bookshelves = null,
        languages = listOf("en"),
        copyright = false,
        media_type = "Text",
        formats = mapOf("text/plain; charset=utf-8" to "https://www.gutenberg.org/files/1/1-0.txt"),
        download_count = 100
    )

    @Test
    fun `gutenberg search maps dto to prefixed book id`() = runBlocking {
        val api = object : GutendexApi {
            override suspend fun searchBooks(query: String) =
                GutendexResponse(count = 1, next = null, previous = null, results = listOf(gutendexDto()))
            override suspend fun getBookById(id: Int) = gutendexDto()
            override suspend fun downloadTextContent(url: String) = throw UnsupportedOperationException()
        }
        val source = GutendexBookSource(api)

        val books = source.search("austen")

        assertEquals(1, books.size)
        assertEquals("gutenberg_1342", books[0].id)
        assertTrue(source.canDownload(books[0]))
        assertTrue(source.owns(books[0]))
    }

    @Test
    fun `gutenberg cleanBookText strips license header and footer`() {
        val raw = """
            Some license text
            *** START OF THE PROJECT GUTENBERG EBOOK PRIDE ***
            Once upon a time.
            *** END OF THE PROJECT GUTENBERG EBOOK
            More license text
        """.trimIndent()

        val cleaned = GutendexBookSource.cleanBookText(raw)
        assertTrue(cleaned.startsWith("Once upon a time."))
        assertFalse(cleaned.contains("GUTENBERG"))
    }

    @Test
    fun `open library maps docs and reports non-downloadable`() = runBlocking {
        val api = object : OpenLibraryApi {
            override suspend fun searchBooks(query: String, limit: Int, fields: String) =
                OpenLibrarySearchResponse(
                    numFound = 1,
                    docs = listOf(
                        OpenLibraryDocDto(
                            key = "/works/OL1168083W",
                            title = "Emma",
                            author_name = listOf("Jane Austen"),
                            first_publish_year = 1815,
                            cover_i = 42
                        )
                    )
                )
        }
        val source = OpenLibraryBookSource(api)

        val books = source.search("emma")

        assertEquals(1, books.size)
        assertEquals("ol_OL1168083W", books[0].id)
        assertEquals("Jane Austen", books[0].author)
        assertEquals("https://covers.openlibrary.org/b/id/42-M.jpg", books[0].coverUrl)
        assertFalse(source.canDownload(books[0]))
    }
}
