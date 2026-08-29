package com.lexiread.core.reader.parsers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lexiread.domain.model.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class EpubParserTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun buildEpub(entries: Map<String, ByteArray>): File {
        val file = File(context.cacheDir, "test_${System.nanoTime()}.epub")
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return file
    }

    private fun containerXml(opfPath: String) = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent().toByteArray()

    private fun opf(spineIds: List<Pair<String, String>>) = buildString {
        appendLine("""<?xml version="1.0"?>""")
        appendLine("""<package xmlns="http://www.idpf.org/2007/opf" version="3.0">""")
        appendLine("<manifest>")
        spineIds.forEach { (id, href) -> appendLine("""<item id="$id" href="$href" media-type="application/xhtml+xml"/>""") }
        appendLine("</manifest>")
        appendLine("<spine>")
        spineIds.forEach { (id, _) -> appendLine("""<itemref idref="$id"/>""") }
        appendLine("</spine>")
        appendLine("</package>")
    }.toByteArray()

    private fun chapterHtml(title: String, body: String) =
        """<html><head><title>x</title></head><body><h1>$title</h1><p>$body</p></body></html>"""
            .toByteArray()

    @Test
    fun `parses chapters from valid epub in spine order`() {
        val epub = buildEpub(
            mapOf(
                "META-INF/container.xml" to containerXml("content.opf"),
                "content.opf" to opf(listOf("c1" to "ch1.xhtml", "c2" to "ch2.xhtml")),
                "ch1.xhtml" to chapterHtml("First", "Hello world."),
                "ch2.xhtml" to chapterHtml("Second", "Goodbye world.")
            )
        )

        val chapters: List<BookChapter> = runBlocking { EpubParser().parseChapters(epub) }

        assertEquals(2, chapters.size)
        assertEquals("First", chapters[0].title)
        assertEquals("Second", chapters[1].title)
        assertTrue(chapters[0].content.contains("Hello"))
        epub.delete()
    }

    @Test
    fun `returns no chapters for corrupt zip`() {
        val bad = File(context.cacheDir, "bad_${System.nanoTime()}.epub")
        bad.writeBytes("not a zip".toByteArray())

        val chapters = runBlocking { EpubParser().parseChapters(bad) }

        assertTrue(chapters.isEmpty())
        bad.delete()
    }

    @Test
    fun `canParse matches extension`() {
        val parser = EpubParser()
        assertTrue(parser.canParse("EPUB", File("x.epub")))
        assertTrue(!parser.canParse("TXT", File("y.txt")))
    }

    /**
     * Regression: chapters used to be decoded in 8 KiB chunks, which split
     * multi-byte characters at the buffer boundary and filled the page with
     * U+FFFD replacement glyphs.
     */
    @Test
    fun `keeps multi-byte characters intact across read buffer boundaries`() {
        val body = "\u041F\u0440\u0438\u0432\u0435\u0442 ".repeat(2000) // ~26 KB of UTF-8
        val epub = buildEpub(
            mapOf(
                "META-INF/container.xml" to containerXml("content.opf"),
                "content.opf" to opf(listOf("c1" to "ch1.xhtml")),
                "ch1.xhtml" to chapterHtml("First", body)
            )
        )

        val chapters = runBlocking { EpubParser().parseChapters(epub) }

        assertEquals(1, chapters.size)
        val content = chapters[0].content
        assertFalse("Text must not contain replacement characters", content.contains('\uFFFD'))
        // Every single occurrence must survive; a split sequence would drop one.
        assertEquals(
            "All 2000 repeats must survive decoding",
            2000,
            Regex("\u041F\u0440\u0438\u0432\u0435\u0442").findAll(content).count()
        )
        epub.delete()
    }

    /**
     * Regression: the encoding declared in the XHTML prolog was ignored, so
     * windows-1251 books rendered as mojibake.
     */
    @Test
    fun `declares windows-1251 chapters using their declared encoding`() {
        val title = "\u0413\u043B\u0430\u0432\u0430 1" // "Глава 1"
        val body = "\u041F\u0440\u0438\u0432\u0435\u0442, \u043C\u0438\u0440!" // "Привет, мир!"
        val doc = """<?xml version="1.0" encoding="windows-1251"?>
            |<html xmlns="http://www.w3.org/1999/xhtml">
            |<head><title>x</title></head>
            |<body><h1>$title</h1><p>$body</p></body>
            |</html>""".trimMargin()

        val epub = buildEpub(
            mapOf(
                "META-INF/container.xml" to containerXml("content.opf"),
                "content.opf" to opf(listOf("c1" to "ch1.xhtml")),
                "ch1.xhtml" to doc.toByteArray(charset("windows-1251"))
            )
        )

        val chapters = runBlocking { EpubParser().parseChapters(epub) }

        assertEquals(1, chapters.size)
        assertEquals(title, chapters[0].title)
        assertTrue("Expected readable Cyrillic text", chapters[0].content.contains(body))
        epub.delete()
    }
}
