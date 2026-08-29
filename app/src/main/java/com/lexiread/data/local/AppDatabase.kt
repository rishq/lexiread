package com.lexiread.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lexiread.data.local.dao.BookDao
import com.lexiread.data.local.dao.BookmarkDao
import com.lexiread.data.local.dao.CatalogCacheDao
import com.lexiread.data.local.dao.CacheDao
import com.lexiread.data.local.dao.ReadingProgressDao
import com.lexiread.data.local.dao.SavedWordDao
import com.lexiread.data.local.entity.AiExplanationEntity
import com.lexiread.data.local.entity.BookEntity
import com.lexiread.data.local.entity.BookmarkEntity
import com.lexiread.data.local.entity.CatalogCacheEntity
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
        AiExplanationEntity::class,
        CatalogCacheEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun savedWordDao(): SavedWordDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun cacheDao(): CacheDao
    abstract fun catalogCacheDao(): CatalogCacheDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_words_word` ON `saved_words` (`word`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_cache` (
                        `cacheKey` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `query` TEXT NOT NULL,
                        `page` INTEGER NOT NULL,
                        `payload` TEXT NOT NULL,
                        `totalResults` INTEGER,
                        `hasMore` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`cacheKey`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
