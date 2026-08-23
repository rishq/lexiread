package com.lexiread.data.repository

import com.lexiread.core.util.runSuspendCatching
import com.lexiread.data.local.PreloadedBooks
import com.lexiread.data.local.dao.BookDao
import com.lexiread.data.local.dao.BookmarkDao
import com.lexiread.data.local.dao.ReadingProgressDao
import com.lexiread.data.local.entity.BookEntity
import com.lexiread.data.local.entity.BookmarkEntity
import com.lexiread.data.local.entity.ReadingProgressEntity
import com.lexiread.domain.repository.BookSource
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.Bookmark
import com.lexiread.domain.model.ReadingProgress
import com.lexiread.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BookRepositoryImpl(
    private val bookDao: BookDao,
    private val readingProgressDao: ReadingProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val sources: List<BookSource>
) : BookRepository {

    suspend fun initializePreloadedBooks() {
        // Pre-populate preloaded classic books if database is empty,
        // or refresh them if they were stored with encoding corruption
        // (older app versions shipped double-encoded UTF-8 strings).
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
        } else if (containsEncodingCorruption(existing)) {
            bookDao.insertBooks(PreloadedBooks.defaultBooks)
        }
    }

    private fun containsEncodingCorruption(entity: com.lexiread.data.local.entity.BookEntity): Boolean {
        return listOfNotNull(entity.title, entity.author, entity.description, entity.subjects, entity.fullText)
            .any { it.contains("вЂ") }
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
        // Aggregate results across all registered sources; a failing source
        // degrades gracefully instead of failing the whole search.
        val results = sources.flatMap { source ->
            runSuspendCatching { source.search(query) }
                .onFailure { android.util.Log.w("BookRepository", "Source ${source.displayName} failed", it) }
                .getOrDefault(emptyList())
        }
        return Result.success(results.distinctBy { it.id })
    }

    override suspend fun fetchAndSaveFullBook(book: Book): Result<Book> {
        return runSuspendCatching {
            val existing = bookDao.getBookById(book.id)
            if (existing != null &&
                (!existing.fullText.isNullOrBlank() || !existing.filePath.isNullOrBlank())
            ) {
                return@runSuspendCatching existing.toDomain()
            }

            val source = sources.firstOrNull { it.owns(book) || it.canDownload(book) }
                ?: throw UnsupportedOperationException(
                    "No online source provides content for '${book.title}' (${book.id})."
                )

            val updatedBook = source.downloadContent(book.copy(isSaved = true))
            if (updatedBook.fullText.isNullOrBlank() && updatedBook.filePath.isNullOrBlank()) {
                throw IllegalStateException("Source returned no readable content for '${book.title}'.")
            }

            updatedBook.let { bookDao.insertBook(it.toEntity()) }
            updatedBook
        }
    }

    override suspend fun addBookToLibrary(book: Book) {
        val entity = book.copy(isSaved = true).toEntity()
        bookDao.insertBook(entity)
    }

    override suspend fun deleteBook(id: String) {
        val book = bookDao.getBookById(id)
        if (book?.filePath != null) {
            runCatching { java.io.File(book.filePath).delete() }
        }
        bookDao.deleteBook(id)
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
}

// Mappers
fun com.lexiread.data.local.entity.BookMeta.toDomain() = Book(
    id = id,
    title = title,
    author = author,
    coverUrl = coverUrl,
    description = description,
    fullText = null,
    filePath = filePath,
    format = format,
    language = language,
    subjects = if (subjects.isBlank()) emptyList() else subjects.split(", "),
    isFavorite = isFavorite,
    isSaved = isSaved,
    isFinished = isFinished,
    isImported = isImported,
    addedTimestamp = addedTimestamp
)

fun BookEntity.toDomain() = Book(
    id = id,
    title = title,
    author = author,
    coverUrl = coverUrl,
    description = description,
    fullText = fullText,
    filePath = filePath,
    format = format,
    language = language,
    subjects = if (subjects.isBlank()) emptyList() else subjects.split(", "),
    isFavorite = isFavorite,
    isSaved = isSaved,
    isFinished = isFinished,
    isImported = isImported,
    addedTimestamp = addedTimestamp
)

fun Book.toEntity() = BookEntity(
    id = id,
    title = title,
    author = author,
    coverUrl = coverUrl,
    description = description,
    fullText = fullText,
    filePath = filePath,
    format = format,
    language = language,
    subjects = subjects.joinToString(", "),
    isFavorite = isFavorite,
    isSaved = isSaved,
    isFinished = isFinished,
    isImported = isImported,
    addedTimestamp = addedTimestamp
)

fun ReadingProgressEntity.toDomain() = ReadingProgress(
    bookId = bookId,
    scrollOffset = scrollOffset,
    currentChapter = currentChapter,
    currentPage = currentPage,
    totalPagesInChapter = totalPagesInChapter,
    totalLength = totalLength,
    percentCompleted = percentCompleted,
    lastReadTimestamp = lastReadTimestamp
)

fun ReadingProgress.toEntity() = ReadingProgressEntity(
    bookId = bookId,
    scrollOffset = scrollOffset,
    currentChapter = currentChapter,
    currentPage = currentPage,
    totalPagesInChapter = totalPagesInChapter,
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
