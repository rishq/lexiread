package com.lexiread.core.reader.parsers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lexiread.domain.model.BookChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Covers two of the 2026-09-18 audit leads: the quadratic `<binary>` cover regex
 * in `extractMetadata`, and the unhardened XML entry points.
 *
 * Every test uses a block body rather than `= runBlocking { … }`: the last
 * statement here is `file.delete()`, which returns a `Boolean`, and a JUnit test
 * method must return `Unit`.
 */
@RunWith(RobolectricTestRunner::class)
class Fb2ParserTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun fb2File(name: String, contents: String): File {
        val file = File(context.cacheDir, "${name}_${System.nanoTime()}.fb2")
        file.writeText(contents, Charsets.UTF_8)
        return file
    }

    private fun onePixelJpegBase64(): String = java.util.Base64.getEncoder()
        .encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))

    /**
     * Runs [block] twice and times only the second run.
     *
     * The first call absorbs Robolectric's per-class initialisation, which costs
     * several seconds and would otherwise be charged to the parse being measured —
     * enough to blow the threshold on its own. Reading these fixtures is
     * side-effect free, so running it twice is safe.
     *
     * The pre-fix regex restarted at every `<binary id=` occurrence and scanned to
     * the end of the document each time, so the fixture took tens of seconds; the
     * index scan takes milliseconds. The threshold sits far above any plausible CI
     * jitter and far below the old cost, which separates the two without being
     * flaky.
     */
    private fun assertCompletesQuickly(limitMillis: Long = 10_000, block: () -> Unit) {
        block()
        val startedAt = System.nanoTime()
        block()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("expected under ${limitMillis}ms but took ${elapsedMillis}ms", elapsedMillis < limitMillis)
    }

    @Test
    fun `parses a plain FB2 into chapters`() {
        val file = fb2File(
            "plain",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <body>
                <section><title><p>Chapter One</p></title><p>Hello reader.</p></section>
              </body>
            </FictionBook>
            """.trimIndent()
        )

        val chapters: List<BookChapter> = runBlocking { Fb2Parser().parseChapters(file) }

        assertEquals(1, chapters.size)
        assertEquals("Chapter One", chapters[0].title)
        assertTrue(chapters[0].content.contains("Hello reader."))
        file.delete()
    }

    /**
     * Real FB2 files carry this DOCTYPE. The hardening must not reject it: only a
     * declaration that can define entities inline is refused.
     */
    @Test
    fun `accepts a DOCTYPE that names an external DTD`() {
        val file = fb2File(
            "external-dtd",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE FictionBook SYSTEM "FictionBook2.dtd">
            <FictionBook>
              <body>
                <section><title><p>With DTD</p></title><p>Body text here.</p></section>
              </body>
            </FictionBook>
            """.trimIndent()
        )

        val chapters = runBlocking { Fb2Parser().parseChapters(file) }

        assertEquals(1, chapters.size)
        assertEquals("With DTD", chapters[0].title)
        assertTrue(chapters[0].content.contains("Body text here."))
        file.delete()
    }

    /**
     * An internal subset is the one place a document can declare entities, so it is
     * refused before the parser sees it and the reference must never expand.
     *
     * As in `SafeXmlTest`, the marker is composed from smaller pieces so it appears
     * nowhere in the declarations: only expansion can produce it.
     */
    @Test
    fun `does not expand an entity declared in an internal subset`() {
        val file = fb2File(
            "entity-chain",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE FictionBook [
              <!ENTITY a "AB">
              <!ENTITY b "&a;&a;&a;&a;&a;">
            ]>
            <FictionBook>
              <body>
                <section><title><p>Chain</p></title><p>&b;</p></section>
              </body>
            </FictionBook>
            """.trimIndent()
        )

        val chapters = runBlocking { Fb2Parser().parseChapters(file) }
        val metadata = runBlocking { Fb2Parser().extractMetadata(file) }

        assertFalse(
            "chapter text must not contain the expansion",
            chapters.any { it.content.contains("ABABABABAB") }
        )
        assertFalse(
            "metadata must not contain the expansion",
            metadata.description.orEmpty().contains("ABABABABAB")
        )
        file.delete()
    }

    @Test
    fun `extracts the binary cover from a well-formed FB2`() {
        val file = fb2File(
            "cover",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook>
              <description><title-info><book-title>With Cover</book-title></title-info></description>
              <body><section><p>Text.</p></section></body>
              <binary id="cover.jpg" content-type="image/jpeg">${onePixelJpegBase64()}</binary>
            </FictionBook>
            """.trimIndent()
        )

        val metadata = runBlocking { Fb2Parser().extractMetadata(file) }

        assertEquals("With Cover", metadata.title)
        assertNotNull("cover should have been written", metadata.coverPath)
        val cover = File(metadata.coverPath!!)
        assertTrue(cover.exists())
        assertEquals(4L, cover.length())
        file.delete()
    }

    /**
     * The lead-3 regression. Every `<binary id="a"/>` here is self-closing, so no
     * `</binary>` exists anywhere in the document; the old regex scanned to the end
     * of the document from each one.
     */
    @Test
    fun `stays linear when the document is full of self-closing binaries`() {
        val binaries = "<binary id=\"a\"/>".repeat(20_000)
        val file = fb2File(
            "many-binaries",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook>
              <body><section><p>Text.</p></section></body>
              $binaries
            </FictionBook>
            """.trimIndent()
        )
        assertTrue("fixture should be a few hundred KB", file.length() > 300_000)

        assertCompletesQuickly {
            val metadata = runBlocking { Fb2Parser().extractMetadata(file) }
            assertNull("a self-closing <binary> must not produce a cover", metadata.coverPath)
        }
        file.delete()
    }

    /**
     * The oversize guard must still fire now that the payload is stripped and
     * measured in a single pass: a `<binary>` over the base64 cap is skipped rather
     * than decoded.
     */
    @Test
    fun `skips a binary whose base64 payload exceeds the cap`() {
        // The cap is 5 MB * 4/3 + 4, so 7.5 M characters is comfortably over it.
        val oversize = "A".repeat(7_500_000)
        val file = fb2File(
            "oversize-binary",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook>
              <body><section><p>Text.</p></section></body>
              <binary id="cover.jpg">$oversize</binary>
            </FictionBook>
            """.trimIndent()
        )

        val metadata = runBlocking { Fb2Parser().extractMetadata(file) }

        assertNull("an oversize <binary> must not become a cover", metadata.coverPath)
        file.delete()
    }
}
