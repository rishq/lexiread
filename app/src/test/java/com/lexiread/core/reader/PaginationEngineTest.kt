package com.lexiread.core.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lexiread.domain.model.BookChapter
import com.lexiread.domain.model.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
class PaginationEngineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = PaginationEngine(context)

    @Test
    fun `short chapter fits on a single page`() = runBlocking {
        val pages = engine.paginateChapter(
            chapter = BookChapter("Ch 1", "A short story.", 0),
            settings = ReaderSettings(),
            availableWidthPx = 1080,
            availableHeightPx = 1800
        )
        assertEquals(1, pages.size)
        assertEquals("A short story.", pages[0].text)
    }

    @Test
    fun `long chapter paginates with no text loss`() = runBlocking {
        val paragraph = ("lorem ipsum dolor sit amet ").repeat(20).trim()
        val content = (1..30).joinToString("\n\n") { "$paragraph $it" }

        val pages = engine.paginateChapter(
            chapter = BookChapter("Ch 1", content, 0),
            settings = ReaderSettings(),
            availableWidthPx = 1080,
            availableHeightPx = 1600
        )

        // NOTE: Robolectric's StaticLayout has simplified line metrics, so the exact
        // page count is not asserted here; text preservation is what matters.
        val joined = pages.joinToString("") { it.text }
        // Every word index marker must survive pagination
        for (i in 1..30) {
            assertTrue("Word marker $i lost", joined.contains(" $i"))
        }
        assertTrue(pages.all { it.totalPagesInChapter == pages.size })
    }

    @Test
    fun `tiny dimensions fall back to single page`() = runBlocking {
        val pages = engine.paginateChapter(
            chapter = BookChapter("Ch 1", "Some content.", 0),
            settings = ReaderSettings(),
            availableWidthPx = 10,
            availableHeightPx = 10
        )
        assertEquals(1, pages.size)
    }

    @Test
    fun `blank chapter yields placeholder page`() = runBlocking {
        val pages = engine.paginateChapter(
            chapter = BookChapter("Ch 1", "", 0),
            settings = ReaderSettings(),
            availableWidthPx = 1080,
            availableHeightPx = 1800
        )
        assertEquals(1, pages.size)
    }
}
