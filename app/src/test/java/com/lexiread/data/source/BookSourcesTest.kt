package com.lexiread.data.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lexiread.data.remote.dto.InternetArchiveFileDto
import com.lexiread.data.remote.gutendex.GutendexApi
import com.lexiread.data.remote.gutendex.GutendexBookDto
import com.lexiread.data.remote.gutendex.GutendexPersonDto
import com.lexiread.data.remote.gutendex.GutendexSearchResponseDto
import com.lexiread.data.remote.openlibrary.OpenLibraryApi
import com.lexiread.data.remote.openlibrary.OpenLibraryDocDto
import com.lexiread.data.remote.openlibrary.OpenLibrarySearchResponseDto
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookSourcesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun gutendexDto(
        formats: Map<String, String> = mapOf(
            "text/plain; charset=utf-8" to "https://www.gutenberg.org/files/1342/1342-0.txt"
        )
    ) = GutendexBookDto(
        id = 1342,
        title = "Pride and Prejudice",
        authors = listOf(GutendexPersonDto(name = "Jane Austen", birth_year = 1775, death_year = 1817)),
        translators = null,
        summaries = null,
        subjects = listOf("Love stories"),
        bookshelves = null,
        languages = listOf("en"),
        copyright = false,
        media_type = "Text",
        formats = formats,
        download_count = 100
    )

    private fun gutendexApi(result: GutendexBookDto): GutendexApi = object : GutendexApi {
        override suspend fun searchBooks(
            query: String,
            page: Int,
            sort: String?,
            languages: String?
        ) = GutendexSearchResponseDto(count = 1, next = null, previous = null, results = listOf(result))

        override suspend fun getBookById(id: Int) = result
        override suspend fun downloadFile(url: String): ResponseBody = throw UnsupportedOperationException()
    }

    @Test
    fun `gutenberg search maps dto to prefixed book id`() = runBlocking {
        val source = GutendexBookSource(gutendexApi(gutendexDto()), context)

        val books = source.search("austen")

        assertEquals(1, books.size)
        assertEquals("gutenberg_1342", books[0].id)
        assertEquals("Jane Austen", books[0].author)
        assertTrue(source.canDownload(books[0]))
        assertTrue(source.owns(books[0]))
    }

    @Test
    fun `gutenberg search prefers epub over plain text`() = runBlocking {
        val dto = gutendexDto(
            formats = mapOf(
                "text/plain; charset=utf-8" to "https://www.gutenberg.org/files/1342/1342-0.txt",
                "image/jpeg" to "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
                "application/epub+zip" to "https://www.gutenberg.org/ebooks/1342.epub.images"
            )
        )
        val source = GutendexBookSource(gutendexApi(dto), context)

        val books = source.search("austen")

        assertEquals("EPUB", books[0].format)
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
    fun `open library search maps works and refuses to download`() = runBlocking {
        val api = object : OpenLibraryApi {
            override suspend fun searchBooks(
                query: String,
                page: Int,
                limit: Int,
                fields: String
            ) = OpenLibrarySearchResponseDto(
                numFound = 1,
                start = 0,
                numFoundExact = true,
                docs = listOf(
                    OpenLibraryDocDto(
                        key = "/works/OL27448W",
                        title = "Pride and Prejudice",
                        author_name = listOf("Jane Austen"),
                        first_publish_year = 1813,
                        cover_i = 8231857,
                        isbn = null,
                        language = listOf("en"),
                        subject = listOf("Fiction"),
                        id_gutenberg = listOf("1342"),
                        number_of_pages_median = 279
                    )
                )
            )

            override suspend fun getWork(workId: String) = throw UnsupportedOperationException()
        }
        val source = OpenLibraryBookSource(api)

        val books = source.search("austen")

        assertEquals(1, books.size)
        assertEquals("ol_OL27448W", books[0].id)
        assertEquals("Jane Austen", books[0].author)
        assertEquals("https://covers.openlibrary.org/b/id/8231857-M.jpg", books[0].coverUrl)
        assertFalse(source.canDownload(books[0]))
    }

    @Test
    fun `open library search returns nothing for a blank query`() = runBlocking {
        val api = object : OpenLibraryApi {
            override suspend fun searchBooks(
                query: String,
                page: Int,
                limit: Int,
                fields: String
            ): OpenLibrarySearchResponseDto = throw AssertionError("Network must not be touched for a blank query")

            override suspend fun getWork(workId: String) = throw UnsupportedOperationException()
        }

        assertTrue(OpenLibraryBookSource(api).search("   ").isEmpty())
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

    // --- OPDS reader hardening ---

    private fun opdsFeed(vararg entries: String): java.io.InputStream =
        (
            """<?xml version="1.0" encoding="utf-8"?>""" +
                """<feed xmlns="http://www.w3.org/2005/Atom">""" +
                entries.joinToString("") +
                "</feed>"
            ).byteInputStream(Charsets.UTF_8)

    private val sampleEntry = """
        <entry>
          <id>https://standardebooks.org/ebooks/jane-austen/pride-and-prejudice</id>
          <title>Pride and Prejudice</title>
          <author><name>Jane Austen</name></author>
          <summary>A novel.</summary>
          <link rel="http://opds-spec.org/acquisition"
                type="application/epub+zip"
                href="/ebooks/jane-austen/pride-and-prejudice/downloads/x.epub"/>
        </entry>
    """.trimIndent()

    @Test
    fun `parses an atom feed into opds entries`() {
        val entries = StandardEbooksBookSource.parseOpdsEntries(opdsFeed(sampleEntry))

        assertEquals(1, entries.size)
        assertEquals("jane-austen~pride-and-prejudice", entries[0].id)
        assertEquals("Pride and Prejudice", entries[0].title)
        assertEquals("Jane Austen", entries[0].author)
        assertEquals("A novel.", entries[0].summary)
        assertEquals(
            "https://standardebooks.org/ebooks/jane-austen/pride-and-prejudice/downloads/x.epub",
            entries[0].epubUrl
        )
    }

    /**
     * The OPDS reader streams a network feed, so the internal-subset rejection
     * (SafeXml control 2) must run on this path too — previously only the
     * parser-level control applied, which fails open if the platform parser
     * rejects `FEATURE_PROCESS_DOCDECL`.
     */
    @Test
    fun `rejects an opds feed declaring an internal dtd subset`() {
        val feed = (
            """<?xml version="1.0"?>""" +
                """<!DOCTYPE feed [<!ENTITY x "EXPANDED">]>""" +
                """<feed xmlns="http://www.w3.org/2005/Atom">$sampleEntry</feed>"""
            ).byteInputStream(Charsets.UTF_8)

        assertThrows(SecurityException::class.java) {
            StandardEbooksBookSource.parseOpdsEntries(feed)
        }
    }

    @Test
    fun `rejects an opds feed larger than the byte cap`() {
        val padding = "x".repeat(5 * 1024 * 1024)
        val feed = opdsFeed("<entry><title>$padding</title></entry>")

        assertThrows(IllegalArgumentException::class.java) {
            StandardEbooksBookSource.parseOpdsEntries(feed)
        }
    }
}
