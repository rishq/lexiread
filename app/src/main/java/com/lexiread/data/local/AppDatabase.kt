package com.lexiread.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lexiread.data.local.dao.BookDao
import com.lexiread.data.local.dao.BookmarkDao
import com.lexiread.data.local.dao.CacheDao
import com.lexiread.data.local.dao.ReadingProgressDao
import com.lexiread.data.local.dao.SavedWordDao
import com.lexiread.data.local.entity.AiExplanationEntity
import com.lexiread.data.local.entity.BookEntity
import com.lexiread.data.local.entity.BookmarkEntity
import com.lexiread.data.local.entity.DictionaryCacheEntity
import com.lexiread.data.local.entity.ReadingProgressEntity
import com.lexiread.data.local.entity.SavedWordEntity
import com.lexiread.data.local.entity.TranslationCacheEntity

@Database(
    entities = [
        BookEntity::class,
        ReadingProgressEntity::class,
        SavedWordEntity::class,
        BookmarkEntity::class,
        DictionaryCacheEntity::class,
        TranslationCacheEntity::class,
        AiExplanationEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun savedWordDao(): SavedWordDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun cacheDao(): CacheDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_words_word` ON `saved_words` (`word`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
            }
        }
    }
}
