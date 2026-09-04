package com.lexiread.data.repository

import com.lexiread.data.local.CatalogCacheSerializer
import com.lexiread.data.local.dao.CatalogCacheDao
import com.lexiread.data.local.entity.CatalogCacheEntity
import com.lexiread.data.remote.api.InternetArchiveApi
import com.lexiread.data.remote.api.MyLibApi
import com.lexiread.data.remote.api.PgaApi
import com.lexiread.data.remote.api.StandardEbooksApi
import com.lexiread.data.remote.dto.InternetArchiveMetadataResponse
import com.lexiread.data.remote.dto.InternetArchiveSearchResponse
import com.lexiread.data.remote.googlebooks.GoogleBooksApi
import com.lexiread.data.remote.gutendex.GutendexApi
import com.lexiread.data.remote.openlibrary.OpenLibraryApi
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.Bookmark
import com.lexiread.domain.model.ReadingProgress
import com.lexiread.domain.model.SourceKind
import com.lexiread.domain.repository.BookRepository
import com.lexiread.domain.repository.BookSource
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Runs the catalogue pipeline against payloads captured from the live APIs
 * rather than hand-written fixtures.
 *
 * Synthetic DTOs are built to match the parser, so they can only prove the
 * parser agrees with itself. Real payloads carry the things fixtures leave out:
 * MIME types with charset parameters, records with `copyright: true`, works
 * without a Gutenberg id, and Google Books' quota errors. Those are exactly the
 * shapes that decide whether a card ends up readable, so the test asserts on
 * the outcome a user would see.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogLivePayloadTest {

    private class StaticCacheDao : CatalogCacheDao {
        override suspend fun get(key: String): CatalogCacheEntity? = null
        override suspend fun insert(entry: CatalogCacheEntity) = Unit
        override suspend fun deleteExpired(expireBefore: Long) = Unit
        override suspend fun clear() = Unit
    }

    private class NoopBookRepository : BookRepository {
        override fun getAllBooks(): Flow<List<Book>> = emptyFlow()
        override fun getFavoriteBooks(): Flow<List<Book>> = emptyFlow()
        override fun getSavedBooks(): Flow<List<Book>> = emptyFlow()
        override fun getFinishedBooks(): Flow<List<Book>> = emptyFlow()
        override suspend fun getBookById(id: String): Book? = null
        override suspend fun searchBooksOnline(query: String) = Result.success(emptyList<Book>())
        override suspend fun fetchAndSaveFullBook(book: Book, forceRefresh: Boolean) = Result.success(book)
        override suspend fun addBookToLibrary(book: Book) = Unit
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

    private class NoopSource(override val idPrefix: String) : BookSource {
        override val displayName = "noop"
        override suspend fun search(query: String): List<Book> = emptyList()
        override fun canDownload(book: Book) = false
        override suspend fun downloadContent(book: Book): Book = book
    }

    private class NoopInternetArchiveApi : InternetArchiveApi {
        override suspend fun searchBooks(
            query: String, fields: String, rows: Int, page: Int, output: String
        ) = InternetArchiveSearchResponse()

        override suspend fun getMetadata(identifier: String) = InternetArchiveMetadataResponse()

        override suspend fun downloadFile(url: String): ResponseBody = throw UnsupportedOperationException()
    }

    private class NoopStandardEbooksApi : StandardEbooksApi {
        override suspend fun searchOpds(url: String): ResponseBody = throw UnsupportedOperationException()
        override suspend fun downloadFile(url: String): ResponseBody = throw UnsupportedOperationException()
    }

    private class NoopPgaApi : PgaApi {
        override suspend fun fetch(url: String): ResponseBody = throw UnsupportedOperationException()
    }

    private class NoopMyLibApi : MyLibApi {
        override suspend fun fetch(url: String): ResponseBody = throw UnsupportedOperationException()
    }

    /** Mirrors RetrofitClient: generated adapters first, reflection as backstop. */
    private fun moshi() = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private fun <T> service(server: MockWebServer, api: Class<T>): T = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(MoshiConverterFactory.create(moshi()))
        .build()
        .create(api)

    private fun body(resource: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(resource))
            .bufferedReader()
            .use { it.readText() }

    private fun json(resource: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body(resource))

    @Test
    fun `real gutendex payload yields readable books`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/books/") -> json("gutendex_sherlock.json")
                request.path.orEmpty().startsWith("/search.json") -> json("openlibrary_sherlock.json")
                // What the live API answers today for an anonymous request.
                else -> MockResponse().setResponseCode(429).setBody("{\"error\":{\"code\":429}}")
            }
        }
        server.start()

        try {
            val repository = BooksRepositoryImpl(
                gutendexApi = service(server, GutendexApi::class.java),
                openLibraryApi = service(server, OpenLibraryApi::class.java),
                googleBooksApi = service(server, GoogleBooksApi::class.java),
                internetArchiveApi = NoopInternetArchiveApi(),
                standardEbooksApi = NoopStandardEbooksApi(),
                pgaApi = NoopPgaApi(),
                myLibApi = NoopMyLibApi(),
                catalogCacheDao = StaticCacheDao(),
                bookRepository = NoopBookRepository(),
                sources = listOf(NoopSource("gutenberg")),
                serializer = CatalogCacheSerializer()
            )

            val page = repository.search("sherlock", 1, SourceKind.entries.toSet())

            val gutenberg = page.books.filter { it.source == SourceKind.GUTENDEX }
            val openLibrary = page.books.filter { it.source == SourceKind.OPEN_LIBRARY }
            val readable = page.books.count { it.canRead }

            println("total=${page.books.size} gutenberg=${gutenberg.size} " +
                "openLibrary=${openLibrary.size} readable=$readable")

            assertTrue("Gutendex results must survive the pipeline", gutenberg.isNotEmpty())
            assertTrue("Public-domain Gutendex books must be readable", readable > 0)
            assertTrue(
                "Readable books sort ahead of metadata-only cards",
                page.books.takeWhile { it.canRead }.size == readable
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `gutendex books expose an epub html or text format`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = json("gutendex_sherlock.json")
        }
        server.start()

        try {
            val api = service(server, GutendexApi::class.java)
            val books = api.searchBooks(query = "sherlock", page = 1).results.orEmpty()

            assertEquals(23, books.size)
            val withEpub = books.count { dto ->
                dto.formats.orEmpty().keys.any { it.startsWith("application/epub") }
            }
            println("books=${books.size} withEpub=$withEpub")

            books.forEach { dto ->
                val mapped = dto.formats.orEmpty().keys
                    .mapNotNull { com.lexiread.core.util.BookFormatSelector.classify(it) }
                assertTrue(
                    "'${dto.title}' offers no readable format: ${dto.formats?.keys}",
                    mapped.isNotEmpty()
                )
            }
        } finally {
            server.shutdown()
        }
    }
}
