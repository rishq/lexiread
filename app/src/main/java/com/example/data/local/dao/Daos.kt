package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.AiExplanationEntity
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.DictionaryCacheEntity
import com.example.data.local.entity.ReadingProgressEntity
import com.example.data.local.entity.SavedWordEntity
import com.example.data.local.entity.TranslationCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY addedTimestamp DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE isFavorite = 1 ORDER BY addedTimestamp DESC")
    fun getFavoriteBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE isSaved = 1 ORDER BY addedTimestamp DESC")
    fun getSavedBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE isFinished = 1 ORDER BY addedTimestamp DESC")
    fun getFinishedBooks(): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooks(books: List<BookEntity>)

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("UPDATE books SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE books SET isFinished = :isFinished WHERE id = :id")
    suspend fun setFinished(id: String, isFinished: Boolean)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: String)
}

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    fun getProgressByBookId(bookId: String): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun getProgressByBookIdSync(bookId: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress ORDER BY lastReadTimestamp DESC LIMIT 1")
    fun getLatestProgress(): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress ORDER BY lastReadTimestamp DESC")
    fun getAllProgress(): Flow<List<ReadingProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: ReadingProgressEntity)
}

@Dao
interface SavedWordDao {
    @Query("SELECT * FROM saved_words ORDER BY dateAdded DESC")
    fun getAllSavedWords(): Flow<List<SavedWordEntity>>

    @Query("SELECT * FROM saved_words WHERE learningStatus = :status ORDER BY dateAdded DESC")
    fun getWordsByStatus(status: String): Flow<List<SavedWordEntity>>

    @Query("SELECT * FROM saved_words WHERE word = :word LIMIT 1")
    suspend fun getWordByText(word: String): SavedWordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWord(word: SavedWordEntity)

    @Query("UPDATE saved_words SET learningStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: Int, status: String)

    @Query("DELETE FROM saved_words WHERE id = :id")
    suspend fun deleteWord(id: Int)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY timestamp DESC")
    fun getBookmarksByBook(bookId: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Int)
}

@Dao
interface CacheDao {
    // Dictionary Cache
    @Query("SELECT * FROM dictionary_cache WHERE word = :word")
    suspend fun getDictionaryCache(word: String): DictionaryCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDictionaryCache(cache: DictionaryCacheEntity)

    // Translation Cache
    @Query("SELECT * FROM translation_cache WHERE cacheKey = :key")
    suspend fun getTranslationCache(key: String): TranslationCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranslationCache(cache: TranslationCacheEntity)

    // AI Explanation Cache
    @Query("SELECT * FROM ai_explanation_cache WHERE cacheKey = :key")
    suspend fun getAiExplanationCache(key: String): AiExplanationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiExplanationCache(cache: AiExplanationEntity)

    // Cache Cleanup (TTL Purge)
    @Query("DELETE FROM ai_explanation_cache WHERE timestamp < :expireBefore")
    suspend fun deleteExpiredAiExplanationCache(expireBefore: Long)

    @Query("DELETE FROM dictionary_cache WHERE timestamp < :expireBefore")
    suspend fun deleteExpiredDictionaryCache(expireBefore: Long)

    @Query("DELETE FROM translation_cache WHERE timestamp < :expireBefore")
    suspend fun deleteExpiredTranslationCache(expireBefore: Long)
}
