package com.example.domain.repository

import com.example.domain.model.AiExplanation
import com.example.domain.model.Book
import com.example.domain.model.Bookmark
import com.example.domain.model.DictionaryEntry
import com.example.domain.model.LearningStatus
import com.example.domain.model.ReadingProgress
import com.example.domain.model.SavedWord
import com.example.domain.model.TranslationResult
import kotlinx.coroutines.flow.Flow

interface BookRepository {
    fun getAllBooks(): Flow<List<Book>>
    fun getFavoriteBooks(): Flow<List<Book>>
    fun getSavedBooks(): Flow<List<Book>>
    fun getFinishedBooks(): Flow<List<Book>>
    suspend fun getBookById(id: String): Book?
    suspend fun searchBooksOnline(query: String): Result<List<Book>>
    suspend fun fetchAndSaveFullBook(book: Book): Result<Book>
    suspend fun addBookToLibrary(book: Book)
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
    suspend fun isWordSaved(word: String): Boolean
    suspend fun saveWord(savedWord: SavedWord)
    suspend fun updateStatus(id: Int, status: LearningStatus)
    suspend fun deleteWord(id: Int)
}

interface AiRepository {
    suspend fun explainWordOrSentence(
        sourceText: String,
        contextSentence: String
    ): Result<AiExplanation>

    suspend fun explainGrammar(sentence: String): Result<String>
}
