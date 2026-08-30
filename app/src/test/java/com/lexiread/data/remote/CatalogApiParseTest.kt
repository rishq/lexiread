package com.lexiread.data.remote

import com.lexiread.data.mapper.toCatalogBook
import com.lexiread.data.remote.googlebooks.GoogleBooksApi
import com.lexiread.data.remote.gutendex.GutendexApi
import com.lexiread.data.remote.openlibrary.OpenLibraryApi
import com.lexiread.data.mapper.openLibraryCoverUrl
import com.lexiread.domain.model.FormatKind
import com.lexiread.domain.model.SourceKind
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Verifies the real JSON contracts of the three catalogues end to end:
 * Retrofit + Moshi + the DTO -> domain mappers.
 *
 * These are the shapes the APIs actually return, so a provider changing a field
 * name fails here instead of silently producing empty book cards.
 */
class CatalogApiParseTest {

    private lateinit var server: MockWebServer
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private lateinit var gutendex: GutendexApi
    private lateinit var openLibrary: OpenLibraryApi
    private lateinit var googleBooks: GoogleBooksApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/")
        val converter = MoshiConverterFactory.create(moshi)
        gutendex = Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(converter)
            .build().create(GutendexApi::class.java)
        openLibrary = Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(converter)
            .build().create(OpenLibraryApi::class.java)
        googleBooks = Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(converter)
            .build().create(GoogleBooksApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        )
    }

    @Test
    fun `gutendex response maps formats covers and summaries`() = runBlocking {
        enqueue(
            """
            {
              "count": 1,
              "next": null,
              "previous": null,
              "results": [{
                "id": 1342,
                "title": "Pride and Prejudice",
                "authors": [{"name": "Austen, Jane", "birth_year": 1775, "death_year": 1817}],
                "summaries": ["A novel of manners."],
                "subjects": ["Love stories"],
                "languages": ["en"],
                "copyright": false,
                "media_type": "Text",
                "formats": {
                  "text/html": "http://www.gutenberg.org/files/1342/1342-h/1342-h.htm",
                  "application/epub+zip": "https://www.gutenberg.org/ebooks/1342.epub.images",
                  "text/plain; charset=utf-8": "https://www.gutenberg.org/files/1342/1342-0.txt",
                  "image/jpeg": "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
                  "application/rdf+xml": "https://www.gutenberg.org/ebooks/1342.rdf"
                },
                "download_count": 52311
              }]
            }
            """.trimIndent()
        )

        val book = gutendex.searchBooks("austen").results!!.first().toCatalogBook()

        assertEquals("gutenberg_1342", book.id)
        assertEquals(SourceKind.GUTENDEX, book.source)
        assertEquals("Jane Austen", book.authorLine)
        assertEquals("A novel of manners.", book.description)
        assertEquals(FormatKind.EPUB, book.formats.first().kind)
        assertEquals(
            listOf(FormatKind.EPUB, FormatKind.HTML, FormatKind.TXT),
            book.formats.map { it.kind }
        )
        assertEquals(
            "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
            book.coverUrl
        )
        assertTrue(book.canRead)
        assertEquals(52_311, book.downloadCount)
    }

    @Test
    fun `gutendex marks a copyrighted book as unreadable`() = runBlocking {
        enqueue(
            """
            {"count":1,"results":[{"id":1,"title":"Modern Book","copyright":true,
             "formats":{"application/epub+zip":"https://www.gutenberg.org/ebooks/1.epub"}}]}
            """.trimIndent()
        )

        val book = gutendex.searchBooks("modern").results!!.first().toCatalogBook()

        assertTrue(!book.canRead)
    }

    @Test
    fun `open library builds cover urls and exposes no downloadable format`() = runBlocking {
        enqueue(
            """
            {
              "numFound": 1,
              "docs": [{
                "key": "/works/OL27448W",
                "title": "Pride and Prejudice",
                "author_name": ["Jane Austen"],
                "first_publish_year": 1813,
                "cover_i": 1234,
                "isbn": ["9780141439518", "0141439513"],
                "language": ["en"],
                "subject": ["Fiction", "Romance"],
                "id_gutenberg": ["1342"]
              }]
            }
            """.trimIndent()
        )

        val book = openLibrary.searchBooks("pride").docs!!.first().toCatalogBook()!!

        assertEquals("ol_OL27448W", book.id)
        assertEquals("https://covers.openlibrary.org/b/id/1234-M.jpg", book.coverUrl)
        assertEquals("9780141439518", book.identifiers.isbn13)
        assertEquals("0141439513", book.identifiers.isbn10)
        assertEquals(1342, book.identifiers.gutenbergId)
        assertEquals(1813, book.publishedYear)
        // Catalogue only: never a download source.
        assertTrue(book.formats.isEmpty())
        assertTrue(!book.canRead)
    }

    @Test
    fun `open library records without a cover fall back to a null cover`() = runBlocking {
        enqueue("""{"numFound":1,"docs":[{"key":"/works/OL1W","title":"No Cover"}]}""")

        val book = openLibrary.searchBooks("x").docs!!.first().toCatalogBook()!!

        assertNull(book.coverUrl)
        assertNull(openLibraryCoverUrl(null))
        assertNull(openLibraryCoverUrl(0))
    }

    @Test
    fun `open library records without a key or title are skipped`() = runBlocking {
        enqueue("""{"numFound":2,"docs":[{"key":"/works/OL1W"},{"title":"No key"}]}""")

        val books = openLibrary.searchBooks("x").docs!!.mapNotNull { it.toCatalogBook() }

        assertTrue(books.isEmpty())
    }

    @Test
    fun `google books maps metadata and never exposes a download link`() = runBlocking {
        enqueue(
            """
            {
              "totalItems": 1,
              "items": [{
                "id": "abc123",
                "volumeInfo": {
                  "title": "Pride and Prejudice",
                  "authors": ["Jane Austen"],
                  "publishedDate": "1813-01-28",
                  "description": "<p>A novel of <b>manners</b>.</p>",
                  "categories": ["Fiction"],
                  "language": "en",
                  "imageLinks": {"thumbnail": "http://books.google.com/books/content?id=abc&printsec=frontcover"},
                  "industryIdentifiers": [
                    {"type": "ISBN_13", "identifier": "9780141439518"},
                    {"type": "ISBN_10", "identifier": "0141439513"}
                  ]
                },
                "accessInfo": {"viewability": "ALL_PAGES", "publicDomain": true}
              }]
            }
            """.trimIndent()
        )

        val book = googleBooks.searchVolumes("pride").items!!.first().toCatalogBook()!!

        assertEquals("gb_abc123", book.id)
        assertEquals(SourceKind.GOOGLE_BOOKS, book.source)
        assertEquals("A novel of manners.", book.description)
        assertEquals(1813, book.publishedYear)
        assertEquals("9780141439518", book.identifiers.isbn13)
        // Even for a public-domain volume we never offer a Google download.
        assertTrue(book.formats.isEmpty())
        assertTrue(book.coverUrl!!.startsWith("https://"))
    }

    @Test
    fun `unknown json fields are ignored instead of failing`() = runBlocking {
        enqueue(
            """
            {"count":1,"someNewField":{"nested":true},
             "results":[{"id":1,"title":"T","anotherNewField":7,"formats":{}}]}
            """.trimIndent()
        )

        val book = gutendex.searchBooks("t").results!!.first().toCatalogBook()

        assertEquals("gutenberg_1", book.id)
        assertTrue(book.formats.isEmpty())
    }

    @Test
    fun `the api key is only sent when configured`() = runBlocking {
        enqueue("""{"totalItems":0,"items":[]}""")
        googleBooks.searchVolumes("x", apiKey = null)
        val withoutKey = server.takeRequest()

        enqueue("""{"totalItems":0,"items":[]}""")
        googleBooks.searchVolumes("x", apiKey = "secret-key")
        val withKey = server.takeRequest()

        assertTrue("no key parameter when unconfigured", withoutKey.path.orEmpty().indexOf("key=") == -1)
        assertTrue("key parameter present when configured", withKey.path.orEmpty().contains("key=secret-key"))
    }
}
