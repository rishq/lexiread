package com.example.data.repository

import com.example.data.local.PreloadedBooks
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.BookmarkDao
import com.example.data.local.dao.ReadingProgressDao
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.ReadingProgressEntity
import com.example.data.remote.api.GutendexApi
import com.example.data.remote.api.OpenLibraryApi
import com.example.domain.model.Book
import com.example.domain.model.Bookmark
import com.example.domain.model.ReadingProgress
import com.example.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BookRepositoryImpl(
    private val bookDao: BookDao,
    private val readingProgressDao: ReadingProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val gutendexApi: GutendexApi,
    private val openLibraryApi: OpenLibraryApi
) : BookRepository {

    suspend fun initializePreloadedBooks() {
        // Pre-populate preloaded classic books if database is empty
        val existing = bookDao.getBookById("gutenberg_1342")
        if (existing == null) {
            bookDao.insertBooks(PreloadedBooks.defaultBooks)
            // Pre-initialize initial reading progress for Pride and Prejudice
            readingProgressDao.saveProgress(
                ReadingProgressEntity(
                    bookId = "gutenberg_1342",
                    scrollOffset = 0,
                    currentChapter = 1,
                    totalLength = PreloadedBooks.defaultBooks.first().fullText?.length ?: 1000,
                    percentCompleted = 5f,
                    lastReadTimestamp = System.currentTimeMillis()
                )
            )
        }
    }

    override fun getAllBooks(): Flow<List<Book>> {
        return bookDao.getAllBooks().map { entities -> entities.map { it.toDomain() } }
    }

    override fun getFavoriteBooks(): Flow<List<Book>> {
        return bookDao.getFavoriteBooks().map { entities -> entities.map { it.toDomain() } }
    }

    override fun getSavedBooks(): Flow<List<Book>> {
        return bookDao.getSavedBooks().map { entities -> entities.map { it.toDomain() } }
    }

    override fun getFinishedBooks(): Flow<List<Book>> {
        return bookDao.getFinishedBooks().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getBookById(id: String): Book? {
        return bookDao.getBookById(id)?.toDomain()
    }

    override suspend fun searchBooksOnline(query: String): Result<List<Book>> {
        return runCatching {
            val response = gutendexApi.searchBooks(query)
            val results = response.results?.map { dto ->
                val authorStr = dto.authors?.joinToString(", ") { it.name ?: "" } ?: "Unknown Author"
                val cover = dto.formats?.get("image/jpeg")
                    ?: "https://www.gutenberg.org/cache/epub/${dto.id}/pg${dto.id}.cover.medium.jpg"
                val textUrl = dto.formats?.get("text/plain; charset=us-ascii")
                    ?: dto.formats?.get("text/plain; charset=utf-8")
                    ?: dto.formats?.get("text/plain")

                Book(
                    id = "gutenberg_${dto.id}",
                    title = dto.title,
                    author = authorStr,
                    coverUrl = cover,
                    description = dto.subjects?.joinToString(" • ") ?: "Classic English Literature",
                    fullText = null, // Will fetch when reading
                    language = dto.languages?.firstOrNull() ?: "en",
                    subjects = dto.subjects ?: emptyList(),
                    isFavorite = false,
                    isSaved = false,
                    isFinished = false
                )
            } ?: emptyList()

            results
        }
    }

    override suspend fun fetchAndSaveFullBook(book: Book): Result<Book> {
        return runCatching {
            val existing = bookDao.getBookById(book.id)
            if (existing != null && !existing.fullText.isNullOrBlank()) {
                return@runCatching existing.toDomain()
            }

            // Extract numeric Gutenberg ID if applicable
            val idNum = book.id.removePrefix("gutenberg_").toIntOrNull()
            var textContent: String? = null

            if (idNum != null) {
                try {
                    val dto = gutendexApi.getBookById(idNum)
                    val textUrl = dto.formats?.get("text/plain; charset=utf-8")
                        ?: dto.formats?.get("text/plain; charset=us-ascii")
                        ?: dto.formats?.get("text/plain")
                        ?: "https://www.gutenberg.org/files/$idNum/$idNum-0.txt"

                    val responseBody = gutendexApi.downloadTextContent(textUrl)
                    textContent = downloadTextSafely(textUrl, responseBody)
                } catch (e: Exception) {
                    // Fallback to sample reader text
                    textContent = book.description ?: "Sample text content for ${book.title}."
                }
            } else {
                textContent = book.fullText ?: book.description ?: "Text content for ${book.title}."
            }

            val cleanedText = cleanBookText(textContent ?: "")
            val updatedBook = book.copy(fullText = cleanedText, isSaved = true)
            bookDao.insertBook(updatedBook.toEntity())
            updatedBook
        }
    }

    override suspend fun addBookToLibrary(book: Book) {
        val entity = book.copy(isSaved = true).toEntity()
        bookDao.insertBook(entity)
    }

    override suspend fun toggleFavorite(bookId: String, isFavorite: Boolean) {
        bookDao.setFavorite(bookId, isFavorite)
    }

    override suspend fun toggleFinished(bookId: String, isFinished: Boolean) {
        bookDao.setFinished(bookId, isFinished)
    }

    override suspend fun saveReadingProgress(progress: ReadingProgress) {
        readingProgressDao.saveProgress(progress.toEntity())
    }

    override fun getReadingProgress(bookId: String): Flow<ReadingProgress?> {
        return readingProgressDao.getProgressByBookId(bookId).map { it?.toDomain() }
    }

    override fun getLatestProgress(): Flow<ReadingProgress?> {
        return readingProgressDao.getLatestProgress().map { it?.toDomain() }
    }

    override fun getBookmarks(bookId: String): Flow<List<Bookmark>> {
        return bookmarkDao.getBookmarksByBook(bookId).map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun addBookmark(bookmark: Bookmark) {
        bookmarkDao.insertBookmark(bookmark.toEntity())
    }

    override suspend fun deleteBookmark(id: Int) {
        bookmarkDao.deleteBookmark(id)
    }

    private fun cleanBookText(rawText: String): String {
        var text = rawText
        // Strip Gutenberg license header/footer if present
        val headerIdx = text.indexOf("*** START OF THE PROJECT GUTENBERG EBOOK")
        if (headerIdx != -1) {
            val endHeaderIdx = text.indexOf("***", headerIdx + 40)
            if (endHeaderIdx != -1) {
                text = text.substring(endHeaderIdx + 3)
            }
        }
        val footerIdx = text.indexOf("*** END OF THE PROJECT GUTENBERG EBOOK")
        if (footerIdx != -1) {
            text = text.substring(0, footerIdx)
        }
        return text.trim()
    }

    private fun downloadTextSafely(url: String, responseBody: okhttp3.ResponseBody): String {
        // SSRF URL Domain Validation
        val uri = java.net.URI(url)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        val isTrustedDomain = scheme == "https" && host != null && (
            host == "gutenberg.org" || host.endsWith(".gutenberg.org") ||
            host == "gutendex.com" || host.endsWith(".gutendex.com") ||
            host == "openlibrary.org" || host.endsWith(".openlibrary.org") ||
            host == "archive.org" || host.endsWith(".archive.org")
        )

        if (!isTrustedDomain) {
            throw SecurityException("Download rejected: URL domain '$host' is not in allowed list.")
        }

        // Max Download Limit (10 MB) to prevent Out-Of-Memory errors
        val maxBytes = 10 * 1024 * 1024L
        val contentLength = responseBody.contentLength()
        if (contentLength > maxBytes) {
            throw IllegalArgumentException("Book content length ($contentLength bytes) exceeds maximum 10MB limit.")
        }

        responseBody.byteStream().use { inputStream ->
            val buffer = java.io.ByteArrayOutputStream()
            val data = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L

            while (inputStream.read(data).also { bytesRead = it } != -1) {
                totalRead += bytesRead
                if (totalRead > maxBytes) {
                    throw IllegalArgumentException("Downloaded content exceeded 10MB limit.")
                }
                buffer.write(data, 0, bytesRead)
            }
            return String(buffer.toByteArray(), Charsets.UTF_8)
        }
    }
}

// Mappers
fun BookEntity.toDomain() = Book(
    id = id,
    title = title,
    author = author,
    coverUrl = coverUrl,
    description = description,
    fullText = fullText,
    language = language,
    subjects = if (subjects.isBlank()) emptyList() else subjects.split(", "),
    isFavorite = isFavorite,
    isSaved = isSaved,
    isFinished = isFinished,
    addedTimestamp = addedTimestamp
)

fun Book.toEntity() = BookEntity(
    id = id,
    title = title,
    author = author,
    coverUrl = coverUrl,
    description = description,
    fullText = fullText,
    language = language,
    subjects = subjects.joinToString(", "),
    isFavorite = isFavorite,
    isSaved = isSaved,
    isFinished = isFinished,
    addedTimestamp = addedTimestamp
)

fun ReadingProgressEntity.toDomain() = ReadingProgress(
    bookId = bookId,
    scrollOffset = scrollOffset,
    currentChapter = currentChapter,
    totalLength = totalLength,
    percentCompleted = percentCompleted,
    lastReadTimestamp = lastReadTimestamp
)

fun ReadingProgress.toEntity() = ReadingProgressEntity(
    bookId = bookId,
    scrollOffset = scrollOffset,
    currentChapter = currentChapter,
    totalLength = totalLength,
    percentCompleted = percentCompleted,
    lastReadTimestamp = lastReadTimestamp
)

fun BookmarkEntity.toDomain() = Bookmark(
    id = id,
    bookId = bookId,
    scrollOffset = scrollOffset,
    snippet = snippet,
    note = note,
    timestamp = timestamp
)

fun Bookmark.toEntity() = BookmarkEntity(
    id = id,
    bookId = bookId,
    scrollOffset = scrollOffset,
    snippet = snippet,
    note = note,
    timestamp = timestamp
)
