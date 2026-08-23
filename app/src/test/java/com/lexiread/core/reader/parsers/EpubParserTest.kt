package com.lexiread.core.reader.parsers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lexiread.domain.model.BookChapter
import org.junit.Assert.assertEquals
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
    fun `returns fallback chapter for corrupt zip`() {
        val bad = File(context.cacheDir, "bad_${System.nanoTime()}.epub")
        bad.writeBytes("not a zip".toByteArray())

        val chapters = runBlocking { EpubParser().parseChapters(bad) }

        assertEquals(1, chapters.size)
        bad.delete()
    }

    @Test
    fun `canParse matches extension`() {
        val parser = EpubParser()
        assertTrue(parser.canParse("EPUB", File("x.epub")))
        assertTrue(!parser.canParse("TXT", File("y.txt")))
    }
}
