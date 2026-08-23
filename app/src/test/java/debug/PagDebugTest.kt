package debug

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lexiread.core.reader.PaginationEngine
import com.lexiread.domain.model.BookChapter
import com.lexiread.domain.model.ReaderSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PagDebugTest {
    @Test fun dbg() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        println("DENSITY=${ctx.resources.displayMetrics.density}")
        val engine = PaginationEngine(ctx)
        val chapterText = ("lorem ipsum dolor sit amet consectetur ".repeat(200)).trim()
        val pages = engine.paginateChapter(BookChapter("t", chapterText, 0), ReaderSettings(), 360, 600)
        println("PAGES360=${pages.size}")
    }
}
