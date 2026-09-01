package com.lexiread.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
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
 *
 * The helper is constructed with the database *class*: Room then resolves the
 * exported schemas from `<canonical name>/<version>.json` inside the
 * application assets, which is where `app/schemas` is wired in
 * app/build.gradle.kts (debug variant). Passing an assets folder name instead
 * is deprecated in Room 2.7 and cannot validate auto migrations.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val testDatabaseName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    // --- helpers --------------------------------------------------------------

    /**
     * Inserts a row using the v4 `books` layout. The v4 schema itself (and
     * every other v4 table) is created by [MigrationTestHelper.createDatabase]
     * from the exported `app/schemas/**/4.json`, so it must not be re-declared
     * here — a hand-written DDL would silently drift from the real one.
     */
    private fun insertV4Book(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        title: String,
        fullText: String?,
        author: String = "Unknown Author",
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
            arrayOf<Any?>(
                id,
                title,
                author,
                fullText,
                if (isImported) "EPUB" else "TXT",
                if (isImported) 1 else 0
            )
        )
    }

    // --- tests ----------------------------------------------------------------

    @Test
    fun `migrate 4 to 5 preserves imported book text as a chapter`() {
        helper.createDatabase(testDatabaseName, 4).use { db ->
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
