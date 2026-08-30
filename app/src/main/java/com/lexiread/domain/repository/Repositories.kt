package com.lexiread.domain.repository

import com.lexiread.core.util.SrsScheduler
import com.lexiread.domain.model.AiExplanation
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.Bookmark
import com.lexiread.domain.model.DictionaryEntry
import com.lexiread.domain.model.LearningStatus
import com.lexiread.domain.model.ReadingProgress
import com.lexiread.domain.model.SavedWord
import com.lexiread.domain.model.TranslationResult
import kotlinx.coroutines.flow.Flow

interface BookRepository {
    fun getAllBooks(): Flow<List<Book>>
    fun getFavoriteBooks(): Flow<List<Book>>
    fun getSavedBooks(): Flow<List<Book>>
    fun getFinishedBooks(): Flow<List<Book>>
    suspend fun getBookById(id: String): Book?
    suspend fun searchBooksOnline(query: String): Result<List<Book>>
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
}

interface DictionaryRepository {
    suspend fun lookupWord(word: String): Result<DictionaryEntry>
}

interface TranslationRepository {
    suspend fun translateText(text: String, targetLang: String = "ru"): Result<TranslationResult>
}

interface VocabularyRepository {
    fun getSavedWords(): Flow<List<SavedWord>>
    fun getWordsByStatus(status: LearningStatus): Flow<List<SavedWord>>
    fun getDueWords(): Flow<List<SavedWord>>
    fun getDueWordCount(): Flow<Int>
    suspend fun isWordSaved(word: String): Boolean
    suspend fun saveWord(savedWord: SavedWord)
    suspend fun updateStatus(id: Int, status: LearningStatus)
    suspend fun reviewWord(id: Int, rating: SrsScheduler.ReviewRating)
    suspend fun deleteWord(id: Int)
}

interface AiRepository {
    suspend fun explainWordOrSentence(
        sourceText: String,
        contextSentence: String
    ): Result<AiExplanation>
}
