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

    @Test
    fun `margin does not shrink the measured text area`() = runBlocking {
        // Margins are applied by the reader to the text field's own bounds, which
        // are passed in here. Re-applying them used to cut usableHeight twice and
        // produced pages that overflowed the canvas.
        val paragraph = ("lorem ipsum dolor sit amet ").repeat(60).trim()
        val content = (1..25).joinToString("\n\n") { "$paragraph $it" }
        suspend fun pageCount(marginDp: Int) = engine.paginateChapter(
            chapter = BookChapter("Ch 1", content, 0),
            settings = ReaderSettings(marginDp = marginDp),
            availableWidthPx = 1080,
            availableHeightPx = 800
        ).size
        assertEquals(pageCount(12), pageCount(40))
    }

    @Test
    fun `larger system font scale fits fewer lines per page`() = runBlocking {
        // Compose scales `sp` with the system font scale; the engine measures in
        // raw px, so it must apply the same scale or pages overflow when a user
        // has increased the device font size.
        val paragraph = ("word one two three four five six seven eight ").repeat(40).trim()
        val content = (1..20).joinToString("\n\n") { "$paragraph $it" }
        val configuration = context.resources.configuration
        val originalScale = configuration.fontScale
        try {
            configuration.fontScale = 1f
            val normal = engine.paginateChapter(
                chapter = BookChapter("Ch 1", content, 0),
                settings = ReaderSettings(),
                availableWidthPx = 1080,
                availableHeightPx = 800
            ).size
            configuration.fontScale = 1.6f
            val large = engine.paginateChapter(
                chapter = BookChapter("Ch 1", content, 0),
                settings = ReaderSettings(),
                availableWidthPx = 1080,
                availableHeightPx = 800
            ).size
            assertTrue("Expected more pages at 1.6x font scale ($normal vs $large)", large > normal)
        } finally {
            configuration.fontScale = originalScale
        }
    }
}
