package com.lexiread.domain.repository

import com.lexiread.domain.model.AiExplanation
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.Bookmark
import com.lexiread.domain.model.DictionaryEntry
import com.lexiread.domain.model.Highlight
import com.lexiread.domain.model.ReadingProgress
import com.lexiread.domain.model.TranslationResult
import kotlinx.coroutines.flow.Flow

interface BookRepository {
    fun getAllBooks(): Flow<List<Book>>
    fun getFavoriteBooks(): Flow<List<Book>>
    fun getSavedBooks(): Flow<List<Book>>
    fun getFinishedBooks(): Flow<List<Book>>
    suspend fun getBookById(id: String): Book?
    /** Re-download content when a previously cached file is corrupt or unreadable. */
    suspend fun fetchAndSaveFullBook(book: Book, forceRefresh: Boolean = false): Result<Book>
    suspend fun addBookToLibrary(book: Book)
    suspend fun deleteBook(id: String)
    suspend fun toggleFavorite(bookId: String, isFavorite: Boolean)
    suspend fun toggleFinished(bookId: String, isFinished: Boolean)
    suspend fun saveReadingProgress(progress: ReadingProgress)
    fun getReadingProgress(bookId: String): Flow<ReadingProgress?>
    fun getLatestProgress(): Flow<ReadingProgress?>
    fun getBookmarks(bookId: String): Flow<List<Bookmark>>
    suspend fun addBookmark(bookmark: Bookmark)
    suspend fun deleteBookmark(id: Int)
    fun getHighlights(bookId: String): Flow<List<Highlight>>
    suspend fun addHighlight(highlight: Highlight)
    suspend fun deleteHighlight(id: Long)
}

interface DictionaryRepository {
    suspend fun lookupWord(word: String): Result<DictionaryEntry>
}

interface TranslationRepository {
    suspend fun translateText(text: String, targetLang: String = "ru"): Result<TranslationResult>
}

interface AiRepository {
    suspend fun explainWordOrSentence(
        sourceText: String,
        contextSentence: String
    ): Result<AiExplanation>
}
