package com.lexiread.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lexiread.data.local.dao.BookDao
import com.lexiread.data.local.dao.BookmarkDao
import com.lexiread.data.local.dao.CatalogCacheDao
import com.lexiread.data.local.dao.CacheDao
import com.lexiread.data.local.dao.ChapterDao
import com.lexiread.data.local.dao.ReadingProgressDao
import com.lexiread.data.local.dao.SavedWordDao
import com.lexiread.data.local.entity.AiExplanationEntity
import com.lexiread.data.local.entity.BookEntity
import com.lexiread.data.local.entity.BookmarkEntity
import com.lexiread.data.local.entity.CatalogCacheEntity
import com.lexiread.data.local.entity.ChapterEntity
import com.lexiread.data.local.entity.DictionaryCacheEntity
import com.lexiread.data.local.entity.ReadingProgressEntity
import com.lexiread.data.local.entity.SavedWordEntity
import com.lexiread.data.local.entity.TranslationCacheEntity

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        SavedWordEntity::class,
        BookmarkEntity::class,
        DictionaryCacheEntity::class,
        TranslationCacheEntity::class,
        AiExplanationEntity::class,
        CatalogCacheEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun savedWordDao(): SavedWordDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun cacheDao(): CacheDao
    abstract fun catalogCacheDao(): CatalogCacheDao

    companion object {
        /**
         * Safety-net migration for the v1→v2 gap.
         *
         * No v1 or v2 schema was ever shipped under the `com.lexiread`
         * package — the database was introduced at version 3. However,
         * if any pre-release or `com.example`-packaged build happens to
         * carry over an old database file, Room would crash with
         * `IllegalStateException` without this migration. The migration
         * is a no-op because v1→v2 added no schema changes that aren't
         * already covered by `MIGRATION_2_3`.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No schema changes between v1 and v2.
            }
        }

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

        /**
         * Removes the `fullText` column from `books`, creates a separate
         * `book_chapters` table, and adds SM-2 spaced repetition fields to
         * `saved_words`.
         *
         * SQLite cannot drop columns directly (pre-3.35), so the migration
         * recreates the books table without the column.
         *
         * Before dropping `fullText`, any non-null text is migrated into the
         * new `book_chapters` table as a single chapter so that user-imported
         * books (EPUB, PDF, FB2, TXT) are not lost. Preloaded books that stored
         * text inline are also migrated; [BookRepositoryImpl.initializePreloadedBooks]
         * will skip re-inserting preloaded books because it checks for the
         * existence of `gutenberg_1342` first.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create the chapters table.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `book_chapters` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `bookId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `chapterIndex` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_chapters_bookId` ON `book_chapters` (`bookId`)")

                // 2. Migrate existing fullText into book_chapters before the
                //    column is dropped. Each book with non-null, non-empty text
                //    gets a single chapter so user-imported content is preserved.
                db.execSQL(
                    """
                    INSERT INTO `book_chapters` (`bookId`, `title`, `content`, `chapterIndex`)
                    SELECT `id`, `title`, `fullText`, 0
                    FROM `books`
                    WHERE `fullText` IS NOT NULL AND LENGTH(`fullText`) > 0
                    """.trimIndent()
                )

                // 3. Recreate books table without the fullText column.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `books_new` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `author` TEXT NOT NULL,
                        `coverUrl` TEXT,
                        `description` TEXT,
                        `filePath` TEXT,
                        `format` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `subjects` TEXT NOT NULL,
                        `isFavorite` INTEGER NOT NULL,
                        `isSaved` INTEGER NOT NULL,
                        `isFinished` INTEGER NOT NULL,
                        `isImported` INTEGER NOT NULL,
                        `addedTimestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `books_new` (id, title, author, coverUrl, description, filePath,
                        format, language, subjects, isFavorite, isSaved, isFinished, isImported,
                        addedTimestamp)
                    SELECT id, title, author, coverUrl, description, filePath,
                        format, language, subjects, isFavorite, isSaved, isFinished, isImported,
                        addedTimestamp
                    FROM `books`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `books`")
                db.execSQL("ALTER TABLE `books_new` RENAME TO `books`")

                // 4. Add SRS fields to saved_words and create the due-words index.
                db.execSQL("ALTER TABLE `saved_words` ADD COLUMN `srsReps` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `saved_words` ADD COLUMN `srsEase` REAL NOT NULL DEFAULT 2.5")
                db.execSQL("ALTER TABLE `saved_words` ADD COLUMN `srsIntervalDays` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `saved_words` ADD COLUMN `lastReviewEpoch` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `saved_words` ADD COLUMN `nextReviewEpoch` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_words_nextReviewEpoch` ON `saved_words` (`nextReviewEpoch`)")
            }
        }
    }
}
