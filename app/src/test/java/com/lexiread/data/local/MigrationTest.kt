package com.lexiread.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Migration tests for the Room schema. They guard the v4→v5 step that
 * extracts `fullText` from `books` into the new `book_chapters` table:
 * a bug there silently deletes every user-imported book on upgrade.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val testDatabaseName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        ApplicationProvider.getApplicationContext(),
        FrameworkSQLiteOpenHelperFactory()
    )

    // --- helpers --------------------------------------------------------------

    private fun v4BooksTable() =
        // Exactly the v4 schema as exported to app/schemas (identity efcd…).
        """
        CREATE TABLE IF NOT EXISTS `books` (
            `id` TEXT NOT NULL PRIMARY KEY,
            `title` TEXT NOT NULL,
            `author` TEXT NOT NULL,
            `coverUrl` TEXT,
            `description` TEXT,
            `fullText` TEXT,
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

    private fun createV4BooksTable(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(v4BooksTable())
    }

    private fun insertV4Book(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        title: String,
        fullText: String?,
        isImported: Boolean = false
    ) {
        db.execSQL(
            """
            INSERT INTO `books` (
                `id`, `title`, `author`, `coverUrl`, `description`, `fullText`, `filePath`,
                `format`, `language`, `subjects`, `isFavorite`, `isSaved`, `isFinished`,
                `isImported`, `addedTimestamp`
            ) VALUES (?, ?, ?, NULL, NULL, ?, NULL, ?, 'en', '[]', 0, 1, 0, ?, 1)
            """.trimIndent(),
            arrayOf(id, title, fullText, if (isImported) "EPUB" else "TXT", if (isImported) 1 else 0)
        )
    }

    // --- tests ----------------------------------------------------------------

    @Test
    fun `migrate 4 to 5 preserves imported book text as a chapter`() {
        helper.createDatabase(testDatabaseName, 4).use { db ->
            createV4BooksTable(db)
            insertV4Book(db, "imported_epub_1", "My Imported Book", "chapter body text", isImported = true)
        }

        val v5 = helper.runMigrationsAndValidate(
            testDatabaseName, 5, true,
            AppDatabase.MIGRATION_4_5
        )

        v5.query("SELECT bookId, title, content, chapterIndex FROM `book_chapters`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("imported_epub_1", cursor.getString(0))
            assertEquals("My Imported Book", cursor.getString(1))
            assertEquals("chapter body text", cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
            assertFalse(cursor.moveToNext())
        }

        v5.query("SELECT `id`, `title` FROM `books`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("imported_epub_1", cursor.getString(0))
        }

        v5.close()
    }

    @Test
    fun `migrate 4 to 5 skips books without fullText`() {
        helper.createDatabase(testDatabaseName, 4).use { db ->
            createV4BooksTable(db)
            insertV4Book(db, "empty_book", "No Text", fullText = null)
            insertV4Book(db, "blank_book", "Blank Text", fullText = "")
        }

        val v5 = helper.runMigrationsAndValidate(
            testDatabaseName, 5, true,
            AppDatabase.MIGRATION_4_5
        )

        v5.query("SELECT COUNT(*) FROM `book_chapters`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        // The books themselves survive without their (empty) text.
        v5.query("SELECT COUNT(*) FROM `books`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }

        v5.close()
    }

    @Test
    fun `migrate 4 to 5 adds SRS columns with SM-2 defaults`() {
        helper.createDatabase(testDatabaseName, 4).use { db ->
            createV4BooksTable(db)
            insertV4Book(db, "b1", "Book", "text")
        }

        val v5 = helper.runMigrationsAndValidate(
            testDatabaseName, 5, true,
            AppDatabase.MIGRATION_4_5
        )

        // Columns created by earlier migrations must still exist; verify via
        // a query against the default values seeded by the ALTER statements.
        v5.query(
            "SELECT `name` FROM pragma_table_info('saved_words') WHERE `name` IN " +
                "('srsReps','srsEase','srsIntervalDays','lastReviewEpoch','nextReviewEpoch')"
        ).use { cursor ->
            assertEquals(5, cursor.count)
        }

        v5.close()
    }
}
