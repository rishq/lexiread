package com.lexiread.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lexiread.data.local.entity.AiExplanationEntity
import com.lexiread.data.local.entity.BookEntity
import com.lexiread.data.local.entity.BookMeta
import com.lexiread.data.local.entity.BookmarkEntity
import com.lexiread.data.local.entity.DictionaryCacheEntity
import com.lexiread.data.local.entity.ReadingProgressEntity
import com.lexiread.data.local.entity.SavedWordEntity
import com.lexiread.data.local.entity.TranslationCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query(
        """
        SELECT id, title, author, coverUrl, description, filePath, format, language,
               subjects, isFavorite, isSaved, isFinished, isImported, addedTimestamp
        FROM books ORDER BY addedTimestamp DESC
        """
    )
    fun getAllBooks(): Flow<List<BookMeta>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: String): BookEntity?

    @Query(
        """
        SELECT id, title, author, coverUrl, description, filePath, format, language,
               subjects, isFavorite, isSaved, isFinished, isImported, addedTimestamp
        FROM books WHERE isFavorite = 1 ORDER BY addedTimestamp DESC
        """
    )
    fun getFavoriteBooks(): Flow<List<BookMeta>>

    @Query(
        """
        SELECT id, title, author, coverUrl, description, filePath, format, language,
               subjects, isFavorite, isSaved, isFinished, isImported, addedTimestamp
        FROM books WHERE isSaved = 1 ORDER BY addedTimestamp DESC
        """
    )
    fun getSavedBooks(): Flow<List<BookMeta>>

    @Query(
        """
        SELECT id, title, author, coverUrl, description, filePath, format, language,
               subjects, isFavorite, isSaved, isFinished, isImported, addedTimestamp
        FROM books WHERE isFinished = 1 ORDER BY addedTimestamp DESC
        """
    )
    fun getFinishedBooks(): Flow<List<BookMeta>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooks(books: List<BookEntity>)

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

    // INNER JOIN books: a progress row whose book was deleted must never be
    // surfaced as "continue reading", otherwise the home screen attributes the
    // percentage to a different book.
    @Query(
        """
        SELECT r.* FROM reading_progress r
        INNER JOIN books b ON r.bookId = b.id
        ORDER BY r.lastReadTimestamp DESC LIMIT 1
        """
    )
    fun getLatestProgress(): Flow<ReadingProgressEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: ReadingProgressEntity)

    @Query("DELETE FROM reading_progress WHERE bookId = :bookId")
    suspend fun deleteProgressForBook(bookId: String)
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

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksForBook(bookId: String)
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
