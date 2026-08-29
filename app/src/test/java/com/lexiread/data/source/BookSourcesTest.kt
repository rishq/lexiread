package com.lexiread.data.source

import com.lexiread.data.remote.api.GutendexApi
import com.lexiread.data.remote.dto.GutendexBookDto
import com.lexiread.data.remote.dto.GutendexPersonDto
import com.lexiread.data.remote.dto.GutendexResponse
import com.lexiread.data.remote.dto.InternetArchiveFileDto
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
    fun `internet archive chooses public epub ahead of text and ignores private files`() {
        val selected = InternetArchiveBookSource.selectReadableFile(
            listOf(
                InternetArchiveFileDto(name = "restricted.epub", format = "EPUB", private = "true"),
                InternetArchiveFileDto(name = "book_djvu.txt", format = "DjVuTXT"),
                InternetArchiveFileDto(name = "book.epub", format = "EPUB"),
                InternetArchiveFileDto(name = "metadata.xml", format = "Metadata")
            )
        )

        assertEquals("book.epub", selected?.name)
    }

    @Test
    fun `internet archive rejects OCR text when no epub is available`() {
        val selected = InternetArchiveBookSource.selectReadableFile(
            listOf(InternetArchiveFileDto(name = "book_djvu.txt", format = "DjVuTXT"))
        )

        assertEquals(null, selected)
    }
}
