package com.lexiread.core.reader.parsers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lexiread.domain.model.BookChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Covers the 2026-09-18 audit lead "PDF text-operator regexes use unbounded lazy
 * quantifiers with a possibly-absent required suffix".
 *
 * The three fixtures below are the reachable worst cases: `(` with no `)` at all,
 * `[` with a single trailing `]`, and a `TJ` array whose body is nothing but `(`.
 * Each cost quadratic time before the scanners replaced the regexes and is linear
 * now, which is what the wall-clock guard separates.
 */
@RunWith(RobolectricTestRunner::class)
class PdfParserTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A minimal PDF whose single stream carries [content] verbatim. */
    private fun pdfFile(name: String, content: String): File {
        val file = File(context.cacheDir, "${name}_${System.nanoTime()}.pdf")
        file.writeText(
            "%PDF-1.4\n1 0 obj\nstream\n$content\nendstream\nendobj\n%%EOF\n",
            Charsets.ISO_8859_1
        )
        return file
    }

    private fun chaptersOf(file: File): List<BookChapter> = runBlocking { PdfParser().parseChapters(file) }

    /**
     * Runs [block] twice and times only the second run.
     *
     * The first call absorbs Robolectric's per-class initialisation, which costs
     * several seconds and would otherwise be charged to the parse being measured —
     * enough to blow the threshold on its own. Parsing these fixtures is read-only,
     * so running it twice is safe.
     *
     * The pre-fix regexes needed tens of seconds on these fixtures and the scanners
     * need milliseconds. The threshold sits far above any plausible CI jitter and
     * far below the old cost, so it separates the two without being flaky.
     */
    private fun assertCompletesQuickly(limitMillis: Long = 10_000, block: () -> Unit) {
        block()
        val startedAt = System.nanoTime()
        block()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("expected under ${limitMillis}ms but took ${elapsedMillis}ms", elapsedMillis < limitMillis)
    }

    @Test
    fun `extracts text from Tj TJ and hex Tj operators`() {
        val file = pdfFile(
            "operators",
            "BT /F1 12 Tf (Hello) Tj [(Wor) -250 (ld)] TJ <4869> Tj ET"
        )

        val content = chaptersOf(file).joinToString("\n") { it.content }

        assertTrue("plain Tj missing: $content", content.contains("Hello"))
        assertTrue("TJ array missing: $content", content.contains("World"))
        assertTrue("hex Tj missing: $content", content.contains("Hi"))
        file.delete()
    }

    @Test
    fun `keeps escaped parentheses inside a literal`() {
        val file = pdfFile("escapes", "BT (a \\(b\\) c) Tj ET")

        val content = chaptersOf(file).joinToString("\n") { it.content }

        assertTrue("escaped parens lost: $content", content.contains("a (b) c"))
        file.delete()
    }

    /** `\((.*?)(?<!\\)\)\s*Tj` with no `)` anywhere: one scan to the end per `(`. */
    @Test
    fun `stays linear when a stream is full of openers with no closer`() {
        val file = pdfFile("openers", "(".repeat(200_000))
        assertTrue("fixture should be ~200 KB", file.length() > 200_000)

        assertCompletesQuickly { chaptersOf(file) }
        file.delete()
    }

    /** `\[(.*?)\]\s*TJ` with one `]` at the very end: the same shape for arrays. */
    @Test
    fun `stays linear when a stream is full of array openers`() {
        val file = pdfFile("array-openers", "[".repeat(200_000) + "]")

        assertCompletesQuickly { chaptersOf(file) }
        file.delete()
    }

    /**
     * The inner-string regex applied to a `TJ` body of nothing but `(`, which is
     * the case that made the array path quadratic independently of the outer match.
     */
    @Test
    fun `stays linear when a TJ array body is full of openers`() {
        val file = pdfFile("array-body-openers", "[" + "(".repeat(100_000) + "] TJ")

        assertCompletesQuickly { chaptersOf(file) }
        file.delete()
    }
}
