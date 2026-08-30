package com.lexiread.data.repository

import android.util.Log
import com.lexiread.core.util.BookFormatSelector
import com.lexiread.core.util.CatalogDeduper
import com.lexiread.core.util.RetryPolicy
import com.lexiread.core.util.runSuspendCatching
import com.lexiread.data.local.CatalogCacheSerializer
import com.lexiread.data.local.dao.CatalogCacheDao
import com.lexiread.data.local.entity.CatalogCacheEntity
import com.lexiread.data.mapper.toCatalogBook
import com.lexiread.data.mapper.toDomainBook
import com.lexiread.data.remote.googlebooks.GoogleBooksApi
import com.lexiread.data.remote.gutendex.GutendexApi
import com.lexiread.data.remote.openlibrary.OpenLibraryApi
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.BookFormat
import com.lexiread.domain.model.CatalogBook
import com.lexiread.domain.model.CatalogPage
import com.lexiread.domain.model.Category
import com.lexiread.domain.model.SourceKind
import com.lexiread.domain.repository.BookRepository
import com.lexiread.domain.repository.BookSource
import com.lexiread.domain.repository.BooksRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * One catalogue façade over three very different APIs.
 *
 * Responsibilities kept here rather than in a ViewModel:
 * - firing the selected sources concurrently and isolating their failures;
 * - merging and deduplicating the results;
 * - paging;
 * - falling back to the last cached page when the network is unavailable.
 *
 * Downloads are delegated to the existing [BookSource] implementations, so the
 * reader, Room and progress tracking keep working exactly as before.
 */
class BooksRepositoryImpl(
    private val gutendexApi: GutendexApi,
    private val openLibraryApi: OpenLibraryApi,
    private val googleBooksApi: GoogleBooksApi,
    private val internetArchiveApi: com.lexiread.data.remote.api.InternetArchiveApi,
    private val standardEbooksApi: com.lexiread.data.remote.api.StandardEbooksApi,
    private val catalogCacheDao: CatalogCacheDao,
    private val bookRepository: BookRepository,
    private val sources: List<BookSource>,
    private val googleBooksApiKey: String? = null,
    private val serializer: CatalogCacheSerializer = CatalogCacheSerializer(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : BooksRepository {

    override fun categories(): List<Category> = DEFAULT_CATEGORIES

    override suspend fun search(query: String, page: Int, sources: Set<SourceKind>): CatalogPage {
        val normalized = query.trim()
        if (normalized.isBlank()) return CatalogPage.empty(page)

        return load(CacheKind.SEARCH, normalized, page, sources) { active ->
            gather(page, buildList {
                if (SourceKind.GUTENDEX in active) add { fetchGutendex(normalized, page) }
                if (SourceKind.OPEN_LIBRARY in active) add { fetchOpenLibrary(normalized, page) }
                if (SourceKind.GOOGLE_BOOKS in active) add { fetchGoogleBooks(normalized, page) }
                if (SourceKind.INTERNET_ARCHIVE in active) add { fetchInternetArchive(normalized, page) }
                if (SourceKind.STANDARD_EBOOKS in active) add { fetchStandardEbooks(normalized, page) }
            })
        }
    }

    override suspend fun getPopular(page: Int, sources: Set<SourceKind>): CatalogPage =
        load(CacheKind.POPULAR, POPULAR_CACHE_KEY, page, sources) { active ->
            gather(page, buildList {
                if (SourceKind.GUTENDEX in active) add { fetchGutendexPopular(page) }
                if (SourceKind.OPEN_LIBRARY in active) add { fetchOpenLibrary(POPULAR_OPEN_LIBRARY_QUERY, page) }
                if (SourceKind.GOOGLE_BOOKS in active) add { fetchGoogleBooks(POPULAR_GOOGLE_BOOKS_QUERY, page) }
                if (SourceKind.INTERNET_ARCHIVE in active) add { fetchInternetArchive(POPULAR_IA_QUERY, page) }
                if (SourceKind.STANDARD_EBOOKS in active) add { fetchStandardEbooks(POPULAR_SE_QUERY, page) }
            })
        }

    override suspend fun getByCategory(
        category: Category,
        page: Int,
        sources: Set<SourceKind>
    ): CatalogPage = load(CacheKind.CATEGORY, category.id, page, sources) { active ->
        gather(page, buildList {
            if (SourceKind.GUTENDEX in active) add { fetchGutendex(category.query, page) }
            if (SourceKind.OPEN_LIBRARY in active) add { fetchOpenLibrary("subject:${category.query}", page) }
            if (SourceKind.GOOGLE_BOOKS in active) add { fetchGoogleBooks("subject:${category.query}", page) }
            if (SourceKind.INTERNET_ARCHIVE in active) add { fetchInternetArchive(category.query, page) }
            if (SourceKind.STANDARD_EBOOKS in active) add { fetchStandardEbooks(category.query, page) }
            })
        }

    override suspend fun getBookDetails(id: String): CatalogBook? = withContext(dispatcher) {
        when {
            id.startsWith(GUTENDEX_PREFIX) -> id.removePrefix(GUTENDEX_PREFIX).toIntOrNull()?.let { bookId ->
                runSuspendCatching { gutendexApi.getBookById(bookId).toCatalogBook() }.getOrNull()
            }
            id.startsWith(OPEN_LIBRARY_PREFIX) -> id.removePrefix(OPEN_LIBRARY_PREFIX).let { workId ->
                runSuspendCatching { openLibraryApi.getWork(workId).toCatalogBook(workId) }.getOrNull()
            }
            id.startsWith(GOOGLE_BOOKS_PREFIX) -> id.removePrefix(GOOGLE_BOOKS_PREFIX).let { volumeId ->
                runSuspendCatching {
                    googleBooksApi.getVolume(volumeId, googleBooksApiKey).toCatalogBook()
                }.getOrNull()
            }
            id.startsWith(INTERNET_ARCHIVE_PREFIX) -> {
                val identifier = id.removePrefix(INTERNET_ARCHIVE_PREFIX)
                runSuspendCatching {
                    val item = internetArchiveApi.getMetadata(identifier)
                    val meta = item.metadata
                    val creator = (item.metadata?.let { it } ?: null)
                    CatalogBook(
                        id = id,
                        title = identifier, // Will be enriched from search results
                        authors = emptyList(),
                        source = SourceKind.INTERNET_ARCHIVE,
                        formats = listOf(
                            com.lexiread.domain.model.BookFormat(
                                com.lexiread.domain.model.FormatKind.EPUB,
                                "application/epub+zip",
                                "https://archive.org/download/$identifier"
                            )
                        ),
                        isPublicDomain = meta?.accessRestricted?.equals("true", ignoreCase = true) != true,
                        identifiers = com.lexiread.domain.model.BookIdentifiers()
                    )
                }.getOrNull()
            }
            id.startsWith(STANDARD_EBOOKS_PREFIX) -> null // SE details resolved from search results
            else -> null
        }
    }

    override suspend fun resolveReadableFormat(book: CatalogBook): BookFormat? {
        val edition = resolveReadableEdition(book)
        return BookFormatSelector.pickBest(edition.formats)
    }

    override suspend fun downloadForReading(book: CatalogBook): Result<Book> = withContext(dispatcher) {
        runSuspendCatching {
            val edition = resolveReadableEdition(book)
            require(edition.canRead) {
                "No free, readable edition is available for '${book.title}'."
            }

            val domain = edition.toDomainBook()
            val source = sources.firstOrNull { it.owns(domain) && it.canDownload(domain) }
                ?: throw UnsupportedOperationException(
                    "No source can supply the text of '${book.title}'."
                )

            val downloaded = source.downloadContent(domain.copy(isSaved = true))
            bookRepository.addBookToLibrary(downloaded)
            downloaded
        }
    }

    /**
     * An Open Library or Google Books record often carries a Project Gutenberg
     * id. Following it turns a metadata-only card into one the user can
     * actually read — this is the main payoff of merging the catalogues.
     */
    private suspend fun resolveReadableEdition(book: CatalogBook): CatalogBook {
        if (book.canRead) return book
        val gutenbergId = book.identifiers.gutenbergId ?: return book
        return runSuspendCatching { gutendexApi.getBookById(gutenbergId).toCatalogBook() }
            .onFailure { Log.w(TAG, "Could not resolve Gutenberg edition $gutenbergId", it) }
            .getOrNull()
            ?.takeIf { it.canRead }
            ?: book
    }

    // --- fetching -----------------------------------------------------------

    private suspend fun fetchGutendex(query: String, page: Int): CatalogPage {
        val response = gutendexApi.searchBooks(query = query, page = page)
        val books = response.results.orEmpty().map { it.toCatalogBook() }
        return CatalogPage(
            books = books,
            page = page,
            totalResults = response.count,
            hasMore = response.next != null
        )
    }

    /**
     * Gutendex's `sort` parameter is undocumented, so the page is also sorted
     * client-side. If the server ignores the parameter the result is still
     * ordered by popularity instead of by internal id.
     */
    private suspend fun fetchGutendexPopular(page: Int): CatalogPage {
        val response = gutendexApi.searchBooks(query = "", page = page, sort = "popular")
        val books = response.results.orEmpty()
            .map { it.toCatalogBook() }
            .sortedByDescending { it.downloadCount ?: 0 }
        return CatalogPage(
            books = books,
            page = page,
            totalResults = response.count,
            hasMore = response.next != null
        )
    }

    private suspend fun fetchOpenLibrary(query: String, page: Int): CatalogPage {
        val response = openLibraryApi.searchBooks(query = query, page = page)
        val books = response.docs.orEmpty().mapNotNull { it.toCatalogBook() }
        val total = response.numFound ?: books.size
        return CatalogPage(
            books = books,
            page = page,
            totalResults = total,
            hasMore = books.isNotEmpty() && page * OpenLibraryApi.DEFAULT_LIMIT < total
        )
    }

    private suspend fun fetchGoogleBooks(query: String, page: Int): CatalogPage {
        val startIndex = (page - 1) * GoogleBooksApi.DEFAULT_MAX_RESULTS
        val response = googleBooksApi.searchVolumes(
            query = query,
            startIndex = startIndex,
            apiKey = googleBooksApiKey
        )
        val books = response.items.orEmpty().mapNotNull { it.toCatalogBook() }
        val total = response.totalItems ?: books.size
        return CatalogPage(
            books = books,
            page = page,
            totalResults = total,
            hasMore = books.isNotEmpty() && startIndex + books.size < total
        )
    }

    private suspend fun fetchInternetArchive(query: String, page: Int): CatalogPage {
        val escaped = query.replace("\\", "\\\\").replace("\"", "\\\"")
        val searchQuery = "mediatype:texts AND format:EPUB AND (title:\"$escaped\" OR creator:\"$escaped\")"
        val response = internetArchiveApi.searchBooks(query = searchQuery, rows = IA_PAGE_SIZE, page = page)
        val books = response.response?.docs.orEmpty().mapNotNull { doc ->
            val identifier = doc.identifier?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = doc.title?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val author = when (val c = doc.creator) {
                is String -> c.trim().takeIf { it.isNotBlank() }
                is List<*> -> c.mapNotNull { it as? String }.joinToString(", ").trim()
                    .takeIf { it.isNotBlank() }
                else -> null
            } ?: "Unknown Author"
            CatalogBook(
                id = INTERNET_ARCHIVE_PREFIX + identifier,
                title = title,
                authors = listOf(com.lexiread.domain.model.Author(author)),
                coverUrl = "https://archive.org/services/img/$identifier",
                description = "Internet Archive edition",
                source = SourceKind.INTERNET_ARCHIVE,
                formats = listOf(
                    com.lexiread.domain.model.BookFormat(
                        com.lexiread.domain.model.FormatKind.EPUB,
                        "application/epub+zip",
                        "https://archive.org/download/$identifier"
                    )
                ),
                isPublicDomain = true,
                identifiers = com.lexiread.domain.model.BookIdentifiers()
            )
        }
        val total = response.response?.numFound ?: books.size
        return CatalogPage(
            books = books,
            page = page,
            totalResults = total,
            hasMore = books.isNotEmpty() && page * IA_PAGE_SIZE < total
        )
    }

    private suspend fun fetchStandardEbooks(query: String, page: Int = 1): CatalogPage =
        withContext(dispatcher) {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://standardebooks.org/feeds/atom/all?query=$encoded" +
                "&page=$page&per-page=$SE_PAGE_SIZE"
            val body = standardEbooksApi.searchOpds(url)
            val entries = com.lexiread.data.source.StandardEbooksBookSource.parseOpdsEntries(
                body.byteStream()
            )
            val books = entries.map { e ->
                CatalogBook(
                    id = STANDARD_EBOOKS_PREFIX + e.id,
                    title = e.title,
                    authors = e.author?.let { listOf(com.lexiread.domain.model.Author(it)) } ?: emptyList(),
                    coverUrl = e.coverUrl,
                    description = e.summary ?: "A Standard Ebooks edition.",
                    source = SourceKind.STANDARD_EBOOKS,
                    formats = listOf(
                        com.lexiread.domain.model.BookFormat(
                            com.lexiread.domain.model.FormatKind.EPUB,
                            "application/epub+zip",
                            e.epubUrl ?: "https://standardebooks.org/ebooks/${e.id.replace("~", "/")}"
                        )
                    ),
                    isPublicDomain = true,
                    identifiers = com.lexiread.domain.model.BookIdentifiers()
                )
            }
            CatalogPage(
                books = books,
                page = page,
                totalResults = books.size,
                hasMore = books.size >= SE_PAGE_SIZE
            )
        }

    /**
     * Runs every source concurrently. One catalogue failing degrades the result
     * set instead of failing the whole search; only a total failure propagates.
     */
    private suspend fun gather(page: Int, jobs: List<suspend () -> CatalogPage>): CatalogPage {
        if (jobs.isEmpty()) return CatalogPage.empty(page)

        val results = coroutineScope {
            jobs.map { job ->
                async {
                    try {
                        Result.success(job())
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
            }.awaitAll()
        }

        val pages = results.mapNotNull { it.getOrNull() }
        val failures = results.mapNotNull { it.exceptionOrNull() }
        failures.forEach { error ->
            Log.w(TAG, "A book catalogue failed", error)
        }

        if (pages.isEmpty()) {
            throw failures.firstOrNull()
                ?: IOException("No book catalogue responded.")
        }

        return CatalogPage(
            // Readable editions first: a card the user can open is worth more
            // than a better-described one they cannot.
            books = CatalogDeduper.dedupe(pages.flatMap { it.books })
                .sortedWith(
                    compareByDescending<CatalogBook> { it.canRead }
                        .thenByDescending { it.downloadCount ?: 0 }
                ),
            page = page,
            totalResults = pages.mapNotNull { it.totalResults }.maxOrNull(),
            hasMore = pages.any { it.hasMore },
            failedSources = failures.map { it.message ?: it::class.java.simpleName }
        )
    }

    // --- caching ------------------------------------------------------------

    /**
     * Offline-first: a fresh non-empty page always wins and refreshes the cache.
     * Only when the network yields nothing do we fall back to what was cached
     * before, so a dead connection shows the previous results rather than an
     * error screen.
     */
    private suspend fun load(
        kind: String,
        cacheQuery: String,
        page: Int,
        sources: Set<SourceKind>,
        fetch: suspend (Set<SourceKind>) -> CatalogPage
    ): CatalogPage = withContext(dispatcher) {
        val key = cacheKey(kind, cacheQuery, page, sources)
        val cached = runSuspendCatching { catalogCacheDao.get(key) }.getOrNull()
        val fresh = runSuspendCatching { RetryPolicy.retryWithBackoff { fetch(sources) } }.getOrNull()

        if (fresh != null && fresh.books.isNotEmpty()) {
            writeCache(key, kind, cacheQuery, page, fresh)
            return@withContext fresh
        }

        val fallback = cached?.let { entry ->
            serializer.deserialize(entry.payload)?.let { books -> entry to books }
        }
        if (fallback != null && fallback.second.isNotEmpty()) {
            Log.w(TAG, "Serving cached catalogue page '$key'.")
            val (entry, books) = fallback
            return@withContext CatalogPage(books, entry.page, entry.totalResults, entry.hasMore)
        }

        // A genuinely empty result is a valid answer; a failure is not.
        if (fresh != null) return@withContext fresh
        throw IOException("No book catalogue could be reached.")
    }

    private suspend fun writeCache(
        key: String,
        kind: String,
        query: String,
        page: Int,
        page1: CatalogPage
    ) {
        runSuspendCatching {
            catalogCacheDao.insert(
                CatalogCacheEntity(
                    cacheKey = key,
                    kind = kind,
                    query = query,
                    page = page,
                    payload = serializer.serialize(page1.books),
                    totalResults = page1.totalResults,
                    hasMore = page1.hasMore,
                    timestamp = System.currentTimeMillis()
                )
            )
        }.onFailure { Log.w(TAG, "Could not cache catalogue page '$key'", it) }
    }

    private fun cacheKey(kind: String, query: String, page: Int, sources: Set<SourceKind>): String =
        buildString {
            append(kind).append('|')
            append(query.lowercase()).append('|')
            append(page).append('|')
            append(sources.map { it.name }.sorted().joinToString(","))
        }

    private companion object {
        const val TAG = "BooksRepository"
        const val GUTENDEX_PREFIX = "gutenberg_"
        const val OPEN_LIBRARY_PREFIX = "ol_"
        const val GOOGLE_BOOKS_PREFIX = "gb_"
        const val INTERNET_ARCHIVE_PREFIX = "ia_"
        const val STANDARD_EBOOKS_PREFIX = "se_"
        const val POPULAR_CACHE_KEY = "popular"
        const val POPULAR_OPEN_LIBRARY_QUERY = "subject:fiction"
        const val POPULAR_GOOGLE_BOOKS_QUERY = "subject:fiction"
        const val POPULAR_IA_QUERY = "fiction"
        const val POPULAR_SE_QUERY = "fiction"
        const val IA_PAGE_SIZE = 20
        const val SE_PAGE_SIZE = 20

        /**
         * Plain, provider-neutral terms. Each fetch wraps them into the query
         * syntax its own API expects.
         */
        val DEFAULT_CATEGORIES = listOf(
            Category("fiction", "Fiction", "fiction"),
            Category("adventure", "Adventure", "adventure"),
            Category("detective", "Detective", "detective"),
            Category("romance", "Romance", "romance"),
            Category("science", "Science", "science"),
            Category("history", "History", "history"),
            Category("philosophy", "Philosophy", "philosophy"),
            Category("poetry", "Poetry", "poetry")
        )
    }
}

/** Cache-key namespace, so a search and a category browse never collide. */
private object CacheKind {
    const val SEARCH = "search"
    const val POPULAR = "popular"
    const val CATEGORY = "category"
}
