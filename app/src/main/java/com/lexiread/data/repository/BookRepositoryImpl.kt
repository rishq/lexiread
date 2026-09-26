package com.lexiread.data.repository

import androidx.room.withTransaction
import com.lexiread.core.util.runSuspendCatching
import com.lexiread.data.local.PreloadedBooks
import com.lexiread.data.local.dao.BookDao
import com.lexiread.data.local.dao.BookmarkDao
import com.lexiread.data.local.dao.ChapterDao
import com.lexiread.data.local.dao.HighlightDao
import com.lexiread.data.local.dao.ReadingProgressDao
import com.lexiread.data.local.entity.BookEntity
import com.lexiread.data.local.entity.BookmarkEntity
import com.lexiread.data.local.entity.HighlightEntity
import com.lexiread.data.local.entity.ReadingProgressEntity
import com.lexiread.domain.repository.BookSource
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.Bookmark
import com.lexiread.domain.model.Highlight
import com.lexiread.domain.model.ReadingProgress
import com.lexiread.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BookRepositoryImpl(
    private val database: com.lexiread.data.local.AppDatabase,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val readingProgressDao: ReadingProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val sources: List<BookSource>
) : BookRepository {

    override suspend fun initializePreloadedBooks() {
        val existing = bookDao.getBookMetaById("gutenberg_1342")
        // Gate on the chapter table too, not just the book row: inserting books
        // and chapters as two separate statements meant a process killed in
        // between left the preloaded books permanently chapter-less, and the book
        // guard then blocked re-seeding on every later launch.
        val chaptersMissing = existing != null && chapterDao.getChapterCount("gutenberg_1342") == 0
        if (existing == null || chaptersMissing) {
            // One transaction, so the seed is all-or-nothing.
            database.withTransaction {
                if (existing == null) {
                    bookDao.insertBooks(PreloadedBooks.defaultBooks)
                }
                if (chaptersMissing || existing == null) {
                    PreloadedBooks.preloaded.forEach { preloaded ->
                        chapterDao.insertChapters(preloaded.chapters)
                    }
                }
                if (existing == null) {
                    readingProgressDao.saveProgress(
                        ReadingProgressEntity(
                            bookId = "gutenberg_1342",
                            scrollOffset = 0,
                            currentChapter = 1,
                            totalLength = 1000,
                            percentCompleted = 5f,
                            lastReadTimestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
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
        return bookDao.getBookMetaById(id)?.toDomain()
    }

    override suspend fun fetchAndSaveFullBook(book: Book, forceRefresh: Boolean): Result<Book> {
        return runSuspendCatching {
            val existing = bookDao.getBookMetaById(book.id)
            if (!forceRefresh && existing != null && !existing.filePath.isNullOrBlank()) {
                return@runSuspendCatching existing.toDomain()
            }

            val source = sources.firstOrNull { it.owns(book) && it.canDownload(book) }
                ?: throw UnsupportedOperationException(
                    "No online source provides content for '${book.title}' (${book.id})."
                )

            val updatedBook = source.downloadContent(book.copy(isSaved = true))
            if (updatedBook.filePath.isNullOrBlank()) {
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
        val book = bookDao.getBookMetaById(id)
        if (book?.filePath != null) {
            runCatching { java.io.File(book.filePath).delete() }
        }
        // Remove dependent rows first: deleting the book alone would leave
        // orphaned reading progress, chapters, and bookmarks behind forever.
        runCatching { readingProgressDao.deleteProgressForBook(id) }
            .onFailure { android.util.Log.w("BookRepository", "Failed to clear progress for $id", it) }
        runCatching { bookmarkDao.deleteBookmarksForBook(id) }
            .onFailure { android.util.Log.w("BookRepository", "Failed to clear bookmarks for $id", it) }
        runCatching { highlightDao.deleteHighlightsForBook(id) }
            .onFailure { android.util.Log.w("BookRepository", "Failed to clear highlights for $id", it) }
        runCatching { chapterDao.deleteChaptersForBook(id) }
            .onFailure { android.util.Log.w("BookRepository", "Failed to clear chapters for $id", it) }
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

    override fun getHighlights(bookId: String): Flow<List<Highlight>> {
        return highlightDao.getHighlightsForBook(bookId).map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun addHighlight(highlight: Highlight) {
        highlightDao.insertHighlight(highlight.toEntity())
    }

    override suspend fun deleteHighlight(id: Long) {
        highlightDao.deleteHighlight(id)
    }
}

// Mappers (BookMeta/BookEntity share fields; single helper avoids drift)
private fun bookToDomain(
    id: String,
    title: String,
    author: String,
    coverUrl: String?,
    description: String?,
    filePath: String?,
    format: String,
    language: String,
    subjects: String,
    isFavorite: Boolean,
    isSaved: Boolean,
    isFinished: Boolean,
    isImported: Boolean,
    addedTimestamp: Long
) = Book(
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

fun com.lexiread.data.local.entity.BookMeta.toDomain() = bookToDomain(
    id, title, author, coverUrl, description, filePath, format, language,
    subjects, isFavorite, isSaved, isFinished, isImported, addedTimestamp
)

fun Book.toEntity() = BookEntity(
    id = id,
    title = title,
    author = author,
    coverUrl = coverUrl,
    description = description,
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

fun HighlightEntity.toDomain() = Highlight(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    startOffset = startOffset,
    endOffset = endOffset,
    colorKey = colorKey,
    createdAt = createdAt
)

fun Highlight.toEntity() = HighlightEntity(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    startOffset = startOffset,
    endOffset = endOffset,
    colorKey = colorKey,
    createdAt = createdAt
)
