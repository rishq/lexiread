package com.lexiread.data.repository

import com.lexiread.data.local.dao.CatalogCacheDao
import com.lexiread.data.local.entity.CatalogCacheEntity
import com.lexiread.data.remote.api.InternetArchiveApi
import com.lexiread.data.remote.api.MyLibApi
import com.lexiread.data.remote.api.PgaApi
import com.lexiread.data.remote.api.StandardEbooksApi
import com.lexiread.data.remote.dto.InternetArchiveMetadataResponse
import com.lexiread.data.remote.dto.InternetArchiveSearchResponse
import com.lexiread.data.remote.googlebooks.GoogleBooksApi
import com.lexiread.data.remote.googlebooks.GoogleBooksIdentifierDto
import com.lexiread.data.remote.googlebooks.GoogleBooksSearchResponseDto
import com.lexiread.data.remote.googlebooks.GoogleBooksVolumeDto
import com.lexiread.data.remote.googlebooks.GoogleBooksVolumeInfoDto
import com.lexiread.data.remote.gutendex.GutendexApi
import com.lexiread.data.remote.gutendex.GutendexBookDto
import com.lexiread.data.remote.gutendex.GutendexPersonDto
import com.lexiread.data.remote.gutendex.GutendexSearchResponseDto
import com.lexiread.data.remote.openlibrary.OpenLibraryApi
import com.lexiread.data.remote.openlibrary.OpenLibraryDocDto
import com.lexiread.data.remote.openlibrary.OpenLibrarySearchResponseDto
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.Bookmark
import com.lexiread.domain.model.CatalogBook
import com.lexiread.domain.model.ReadingProgress
import com.lexiread.domain.model.SourceKind
import com.lexiread.domain.repository.BookRepository
import com.lexiread.domain.repository.BookSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Robolectric rather than plain JUnit: the repository is part of the app
 * module, so [android.util.Log] is a real (shadowed) call and the failure paths
 * can be exercised instead of blowing up on an unmocked Android stub.
 */
@RunWith(RobolectricTestRunner::class)
class BooksRepositoryImplTest {

    // --- fakes -------------------------------------------------------------

    private class FakeGutendexApi : GutendexApi {
        var searchResponse = GutendexSearchResponseDto(results = emptyList(), count = 0, next = null)
        var book = gutendexDto()
        var fail = false

        override suspend fun searchBooks(query: String, page: Int, sort: String?, languages: String?) =
            searchResponse.also { if (fail) throw IOException("gutendex down") }

        override suspend fun getBookById(id: Int) = book.also { if (fail) throw IOException("gutendex down") }

        override suspend fun downloadFile(url: String): ResponseBody = throw UnsupportedOperationException()
    }

    private class FakeOpenLibraryApi : OpenLibraryApi {
        var searchResponse = OpenLibrarySearchResponseDto(docs = emptyList(), numFound = 0)
        var fail = false

        override suspend fun searchBooks(query: String, page: Int, limit: Int, fields: String) =
            searchResponse.also { if (fail) throw IOException("open library down") }

        override suspend fun getWork(workId: String) = throw UnsupportedOperationException()
    }

    private class FakeGoogleBooksApi : GoogleBooksApi {
        var searchResponse = GoogleBooksSearchResponseDto(items = emptyList(), totalItems = 0)
        var fail = false

        override suspend fun searchVolumes(query: String, startIndex: Int, maxResults: Int, apiKey: String?) =
            searchResponse.also { if (fail) throw IOException("google books down") }

        override suspend fun getVolume(volumeId: String, apiKey: String?) = throw UnsupportedOperationException()
    }

    private class FakeInternetArchiveApi : InternetArchiveApi {
        override suspend fun searchBooks(
            query: String, fields: String, rows: Int, page: Int, output: String
        ) = InternetArchiveSearchResponse()

        override suspend fun getMetadata(identifier: String) = InternetArchiveMetadataResponse()

        override suspend fun downloadFile(url: String): ResponseBody = throw UnsupportedOperationException()
    }

    private class FakeStandardEbooksApi : StandardEbooksApi {
        override suspend fun searchOpds(url: String): ResponseBody = throw UnsupportedOperationException()
        override suspend fun downloadFile(url: String): ResponseBody = throw UnsupportedOperationException()
    }

    private class FakePgaApi(var index: String = "") : PgaApi {
        override suspend fun fetch(url: String): ResponseBody =
            ResponseBody.create("text/plain".toMediaType(), index)
    }

    /**
     * Returns an empty search page: with no result rows the parser finds
     * nothing, which is the honest answer for a catalogue that has no fixture.
     */
    private class FakeMyLibApi : MyLibApi {
        override suspend fun fetch(url: String): ResponseBody =
            ResponseBody.create("text/html".toMediaType(), "<html><body></body></html>")
    }

    private class FakeCatalogCacheDao : CatalogCacheDao {
        val entries = LinkedHashMap<String, CatalogCacheEntity>()
        override suspend fun get(key: String) = entries[key]
        override suspend fun insert(entry: CatalogCacheEntity) { entries[entry.cacheKey] = entry }
        override suspend fun deleteExpired(expireBefore: Long) {
            entries.values.removeIf { it.timestamp < expireBefore }
        }
        override suspend fun clear() { entries.clear() }
    }

    private class FakeBookSource(override val idPrefix: String) : BookSource {
        override val displayName = "Fake"
        override suspend fun search(query: String): List<Book> = emptyList()
        override fun canDownload(book: Book) = book.id.startsWith(idPrefix)
        override suspend fun downloadContent(book: Book) =
            book.copy(filePath = "/tmp/${book.id}.epub", format = "EPUB", isSaved = true)
    }

    private class FakeBookRepository : BookRepository {
        val saved = mutableListOf<Book>()
        override fun getAllBooks(): Flow<List<Book>> = emptyFlow()
        override fun getFavoriteBooks(): Flow<List<Book>> = emptyFlow()
        override fun getSavedBooks(): Flow<List<Book>> = emptyFlow()
        override fun getFinishedBooks(): Flow<List<Book>> = emptyFlow()
        override suspend fun getBookById(id: String): Book? = null
        override suspend fun searchBooksOnline(query: String) = Result.success(emptyList<Book>())
        override suspend fun fetchAndSaveFullBook(book: Book, forceRefresh: Boolean) = Result.success(book)
        override suspend fun addBookToLibrary(book: Book) { saved += book }
        override suspend fun deleteBook(id: String) = Unit
        override suspend fun toggleFavorite(bookId: String, isFavorite: Boolean) = Unit
        override suspend fun toggleFinished(bookId: String, isFinished: Boolean) = Unit
        override suspend fun saveReadingProgress(progress: ReadingProgress) = Unit
        override fun getReadingProgress(bookId: String): Flow<ReadingProgress?> = emptyFlow()
        override fun getLatestProgress(): Flow<ReadingProgress?> = emptyFlow()
        override fun getBookmarks(bookId: String): Flow<List<Bookmark>> = emptyFlow()
        override suspend fun addBookmark(bookmark: Bookmark) = Unit
        override suspend fun deleteBookmark(id: Int) = Unit
    }

    // --- fixtures ----------------------------------------------------------

    private companion object {
        fun gutendexDto(
            id: Int = 1342,
            title: String = "Pride and Prejudice",
            author: String = "Austen, Jane",
            formats: Map<String, String> = mapOf(
                "application/epub+zip" to "https://www.gutenberg.org/ebooks/$id.epub.images"
            )
        ) = GutendexBookDto(
            id = id,
            title = title,
            authors = listOf(GutendexPersonDto(name = author, birth_year = 1775, death_year = 1817)),
            languages = listOf("en"),
            copyright = false,
            formats = formats,
            download_count = 40_000
        )

        fun openLibraryDoc(key: String = "/works/OL27448W", title: String = "Pride and Prejudice") =
            OpenLibraryDocDto(
                key = key,
                title = title,
                author_name = listOf("Jane Austen"),
                cover_i = 1_234,
                first_publish_year = 1813,
                isbn = listOf("9780141439518"),
                id_gutenberg = listOf("1342")
            )

        fun googleVolume(id: String = "abc", title: String = "Pride and Prejudice") =
            GoogleBooksVolumeDto(
                id = id,
                volumeInfo = GoogleBooksVolumeInfoDto(
                    title = title,
                    authors = listOf("Jane Austen"),
                    industryIdentifiers = listOf(GoogleBooksIdentifierDto("ISBN_13", "9780141439518"))
                )
            )

        const val PGA_INDEX_FIXTURE = """
            Project Gutenberg Australia
            List of FREE ebooks

            Sep 2024 The Test Book, by Jane Author                [240026xx.xxx] 4553A
            http://gutenberg.net.au/ebooks24/2400261.txt
            http://gutenberg.net.au/ebooks24/2400261h.html

            Aug 2001 Another Tale, by John Writer                 [010003xx.xxx] 0003A
            [Author: John Writer]
            http://gutenberg.net.au/ebooks01/0100031.txt
            http://gutenberg.net.au/ebooks01/0100031h.html
        """
    }

    private fun repository(
        gutendex: FakeGutendexApi = FakeGutendexApi(),
        openLibrary: FakeOpenLibraryApi = FakeOpenLibraryApi(),
        google: FakeGoogleBooksApi = FakeGoogleBooksApi(),
        cache: FakeCatalogCacheDao = FakeCatalogCacheDao(),
        library: FakeBookRepository = FakeBookRepository(),
        pgaApi: FakePgaApi = FakePgaApi(),
        myLibApi: MyLibApi = FakeMyLibApi()
    ) = BooksRepositoryImpl(
        gutendexApi = gutendex,
        openLibraryApi = openLibrary,
        googleBooksApi = google,
        internetArchiveApi = FakeInternetArchiveApi(),
        standardEbooksApi = FakeStandardEbooksApi(),
        pgaApi = pgaApi,
        myLibApi = myLibApi,
        catalogCacheDao = cache,
        bookRepository = library,
        sources = listOf(FakeBookSource("gutenberg")),
        serializer = com.lexiread.data.local.CatalogCacheSerializer()
    )

    // --- tests -------------------------------------------------------------

    @Test
    fun `merges the same edition found in all three catalogues into one card`() = runBlocking {
        val gutendex = FakeGutendexApi().apply {
            searchResponse = GutendexSearchResponseDto(results = listOf(gutendexDto()), count = 1, next = null)
        }
        val openLibrary = FakeOpenLibraryApi().apply {
            searchResponse = OpenLibrarySearchResponseDto(docs = listOf(openLibraryDoc()), numFound = 1)
        }
        val google = FakeGoogleBooksApi().apply {
            searchResponse = GoogleBooksSearchResponseDto(items = listOf(googleVolume()), totalItems = 1)
        }

        val page = repository(gutendex, openLibrary, google).search("pride", 1, allSources())

        assertEquals(1, page.books.size)
        val book = page.books[0]
        assertTrue(book.canRead)
        assertEquals("Jane Austen", book.authorLine)
    }

    @Test
    fun `one broken catalogue degrades instead of failing the search`() = runBlocking {
        val gutendex = FakeGutendexApi().apply {
            searchResponse = GutendexSearchResponseDto(results = listOf(gutendexDto()), count = 1, next = null)
        }
        val google = FakeGoogleBooksApi().apply { fail = true }

        val page = repository(gutendex = gutendex, google = google).search("pride", 1, allSources())

        assertEquals(1, page.books.size)
        assertEquals(SourceKind.GUTENDEX, page.books[0].source)
    }

    @Test
    fun `a total outage raises an error rather than an empty list`() = runBlocking {
        // Only the original three catalogues are faked with failure modes;
        // IA and SE are noop fakes that return empty results. Querying only
        // the three faked sources ensures the outage is total across all
        // queried sources, so gather() has no survivors and must throw.
        val result = runCatching {
            repository(
                gutendex = FakeGutendexApi().apply { fail = true },
                openLibrary = FakeOpenLibraryApi().apply { fail = true },
                google = FakeGoogleBooksApi().apply { fail = true }
            ).search("pride", 1, setOf(
                SourceKind.GUTENDEX, SourceKind.OPEN_LIBRARY, SourceKind.GOOGLE_BOOKS
            ))
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `serves the cached page when the network goes away`() = runBlocking {
        val gutendex = FakeGutendexApi().apply {
            searchResponse = GutendexSearchResponseDto(results = listOf(gutendexDto()), count = 1, next = null)
        }
        val repository = repository(gutendex = gutendex)

        val first = repository.search("pride", 1, allSources())
        assertEquals(1, first.books.size)

        gutendex.fail = true
        val second = repository.search("pride", 1, allSources())

        assertEquals(1, second.books.size)
        assertEquals("Pride and Prejudice", second.books[0].title)
    }

    @Test
    fun `a blank query never touches the network`() = runBlocking {
        val gutendex = FakeGutendexApi().apply { fail = true }

        val page = repository(gutendex = gutendex).search("   ", 1, allSources())

        assertTrue(page.books.isEmpty())
    }

    @Test
    fun `only the selected sources are queried`() = runBlocking {
        val google = FakeGoogleBooksApi().apply {
            searchResponse = GoogleBooksSearchResponseDto(items = listOf(googleVolume()), totalItems = 1)
        }

        val page = repository(google = google).search("pride", 1, setOf(SourceKind.GOOGLE_BOOKS))

        assertEquals(1, page.books.size)
        assertEquals(SourceKind.GOOGLE_BOOKS, page.books[0].source)
    }

    @Test
    fun `an open library card becomes readable through its gutenberg id`() = runBlocking {
        val openLibrary = FakeOpenLibraryApi().apply {
            searchResponse = OpenLibrarySearchResponseDto(
                docs = listOf(openLibraryDoc()),
                numFound = 1
            )
        }
        val gutendex = FakeGutendexApi().apply { book = gutendexDto() }
        val library = FakeBookRepository()

        val page = repository(gutendex = gutendex, openLibrary = openLibrary, library = library)
            .search("pride", 1, setOf(SourceKind.OPEN_LIBRARY))
        val card = page.books.first()

        val result = repository(gutendex = gutendex, library = library).downloadForReading(card)

        assertTrue(result.isSuccess)
        assertEquals(1, library.saved.size)
        assertTrue(library.saved[0].filePath!!.endsWith(".epub"))
    }

    @Test
    fun `refuses to download a book with no free edition`() = runBlocking {
        val google = FakeGoogleBooksApi().apply {
            searchResponse = GoogleBooksSearchResponseDto(items = listOf(googleVolume()), totalItems = 1)
        }

        val page = repository(google = google).search("pride", 1, setOf(SourceKind.GOOGLE_BOOKS))
        val result = repository(google = google).downloadForReading(page.books.first())

        assertTrue(result.isFailure)
    }

    @Test
    fun `pagination reports whether another page exists`() = runBlocking {
        val gutendex = FakeGutendexApi().apply {
            searchResponse = GutendexSearchResponseDto(
                results = listOf(gutendexDto()),
                count = 100,
                next = "https://gutendex.com/books/?page=2"
            )
        }

        val page = repository(gutendex = gutendex).search("pride", 1, setOf(SourceKind.GUTENDEX))

        assertTrue(page.hasMore)
        assertEquals(100, page.totalResults)
    }

    @Test
    fun `categories are provider neutral and non empty`() = runBlocking {
        val categories = repository().categories()

        assertTrue(categories.isNotEmpty())
        assertTrue(categories.none { it.query.contains(':') })
    }

    @Test
    fun `gutendex details are fetched by numeric id`() = runBlocking {
        val gutendex = FakeGutendexApi().apply { book = gutendexDto(id = 84, title = "Frankenstein") }

        val details: CatalogBook? = repository(gutendex = gutendex).getBookDetails("gutenberg_84")

        assertEquals("Frankenstein", details?.title)
        assertTrue(details?.canRead == true)
    }

    @Test
    fun `project gutenberg australia results appear when selected`() = runBlocking {
        val pga = FakePgaApi(PGA_INDEX_FIXTURE)
        val page = repository(pgaApi = pga)
            .search("tale", 1, setOf(SourceKind.GUTENDEX_AUSTRALIA))

        assertEquals(1, page.books.size)
        val book = page.books.first()
        assertEquals(SourceKind.GUTENDEX_AUSTRALIA, book.source)
        assertEquals("pga_0100031", book.id)
        assertEquals("Another Tale", book.title)
        assertTrue(book.canRead)
    }

    @Test
    fun `my library results appear when selected`() = runBlocking {
        val page = repository(myLibApi = FakeMyLibApiFixture())
            .search("pride", 1, setOf(SourceKind.MY_LIB))

        assertEquals(1, page.books.size)
        val book = page.books.first()
        assertEquals(SourceKind.MY_LIB, book.source)
        assertEquals("mylib_12345", book.id)
        assertEquals("Pride and Prejudice", book.title)
        assertEquals("Jane Austen", book.authorLine)
        // EPUB and TXT links were on the page, so the card is openable.
        assertTrue(book.canRead)
        assertEquals(431, page.totalResults)
        assertTrue(page.hasMore)
    }

    @Test
    fun `my library contributes nothing to the popular list`() = runBlocking {
        // The site has no "popular" route, so a blank query must be skipped
        // instead of asking for the whole catalogue.
        val page = repository().getPopular(1, setOf(SourceKind.MY_LIB))

        assertTrue(page.books.isEmpty())
        assertTrue(!page.hasMore)
    }

    /** A stand-in for one scraped search page. */
    private class FakeMyLibApiFixture : MyLibApi {
        override suspend fun fetch(url: String): ResponseBody = ResponseBody.create(
            "text/html".toMediaType(),
            """
            <html><body>
              <div class="book-item">
                <img class="cover" src="/covers/abc.jpg">
                <h3 class="title"><a href="/book/12345/pride-and-prejudice">Pride and Prejudice</a></h3>
                <span class="author">Jane Austen</span>
                <a href="/download/12345.epub">EPUB</a>
                <a href="/download/12345.txt">TXT</a>
              </div>
              <div class="pagination">
                <span>Results 1-1 of 431</span>
                <a href="/s/?q=pride&amp;page=2">Next</a>
              </div>
            </body></html>
            """.trimIndent()
        )
    }

    private fun allSources(): Set<SourceKind> = SourceKind.entries.toSet()
}
