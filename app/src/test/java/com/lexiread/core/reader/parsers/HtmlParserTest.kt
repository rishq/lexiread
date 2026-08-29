package com.lexiread.core.reader.parsers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HtmlParserTest {

    private lateinit var context: Context
    private val parser = HtmlParser()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun write(raw: String, name: String = "book.html"): File =
        File(context.cacheDir, name).apply { writeText(raw, Charsets.UTF_8) }

    private val document = """
        <html>
          <head>
            <title>Pride and Prejudice</title>
            <meta name="author" content="Jane Austen" />
            <style>body { color: red }</style>
            <script>alert('x')</script>
          </head>
          <body>
            <h1>Chapter 1</h1>
            <p>It is a truth universally acknowledged.</p>
            <h2>Chapter 2</h2>
            <p>Mr. Bingley was obliged to wait.</p>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun `recognizes html by format and extension`() {
        val file = write(document)

        assertTrue(parser.canParse("html", file))
        assertTrue(parser.canParse("HTML", file))
        assertTrue(parser.canParse("htm", file))
        assertTrue(parser.canParse("", File(context.cacheDir, "x.html")))
    }

    @Test
    fun `splits chapters on headings`() = runBlocking {
        val chapters = parser.parseChapters(write(document))

        assertEquals(2, chapters.size)
        assertEquals("Chapter 1", chapters[0].title)
        assertEquals("Chapter 2", chapters[1].title)
        assertEquals(0, chapters[0].index)
        assertEquals(1, chapters[1].index)
    }

    @Test
    fun `drops script style and markup from the text`() = runBlocking {
        val chapters = parser.parseChapters(write(document))

        val body = chapters.joinToString(" ") { it.content }
        assertTrue(body.contains("truth universally acknowledged"))
        assertTrue("alert" !in body)
        assertTrue("color: red" !in body)
        assertTrue("<p>" !in body)
    }

    @Test
    fun `title page before the first heading is not turned into a chapter`() = runBlocking {
        val html = """
            <html><body>
              <h3>Pride and Prejudice</h3>
              <p>by Jane Austen</p>
              <h1>Chapter 1</h1><p>Real content.</p>
            </body></html>
        """.trimIndent()

        val chapters = parser.parseChapters(write(html))

        assertEquals(2, chapters.size)
        assertTrue(chapters[1].content.contains("Real content"))
    }

    @Test
    fun `treats a document without headings as a single chapter`() = runBlocking {
        val html = "<html><body><p>Only one long paragraph.</p></body></html>"

        val chapters = parser.parseChapters(write(html))

        assertEquals(1, chapters.size)
        assertEquals("Chapter 1", chapters[0].title)
        assertTrue(chapters[0].content.contains("Only one long paragraph"))
    }

    @Test
    fun `reads metadata from head tags`() = runBlocking {
        val metadata = parser.extractMetadata(write(document))

        assertEquals("Pride and Prejudice", metadata.title)
        assertEquals("Jane Austen", metadata.author)
    }

    @Test
    fun `declares windows 1251 html correctly`() = runBlocking {
        val cyrillic = "Привет, мир!"
        val bytes = cyrillic.toByteArray(charset("windows-1251"))
        val html = "<html><head><meta charset=\"windows-1251\"></head><body>" +
            "<h1>Глава 1</h1><p>" + String(bytes, charset("windows-1251")) + "</p></body></html>"

        val file = File(context.cacheDir, "cp1251.html").apply {
            writeBytes(html.toByteArray(charset("windows-1251")))
        }

        val chapters = parser.parseChapters(file)

        assertTrue(chapters.isNotEmpty())
        val content = chapters.joinToString(" ") { it.content }
        assertTrue(content.contains(cyrillic))
        assertTrue('\uFFFD' !in content)
    }

    @Test
    fun `returns no chapters for an empty file instead of crashing`() = runBlocking {
        assertTrue(parser.parseChapters(write("", "empty.html")).isEmpty())
    }
}
