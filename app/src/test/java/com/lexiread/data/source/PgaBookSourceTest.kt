package com.lexiread.data.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lexiread.data.remote.api.PgaApi
import com.lexiread.domain.model.Book
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PgaBookSourceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun fakeApi(
        index: String,
        fileContent: String = "Project Gutenberg Australia\n\nThe real body text begins here."
    ): PgaApi = object : PgaApi {
        override suspend fun fetch(url: String): ResponseBody {
            val body = if (url.contains("gutindex", ignoreCase = true)) index else fileContent
            return ResponseBody.create("text/plain".toMediaType(), body)
        }
    }

    @Test
    fun `parseIndex extracts number title author and https urls`() {
        val entries = PgaBookSource.parseIndex(INDEX_FIXTURE)

        assertEquals(2, entries.size)

        assertEquals("2400261", entries[0].number)
        assertEquals("The Test Book", entries[0].title)
        assertEquals("Jane Author", entries[0].author)
        assertEquals("https://gutenberg.net.au/ebooks24/2400261.txt", entries[0].txtUrl)
        assertEquals("https://gutenberg.net.au/ebooks24/2400261h.html", entries[0].htmlUrl)

        // The [Author: ...] override must win over the "by ..." in the title line.
        assertEquals("0100031", entries[1].number)
        assertEquals("Another Tale", entries[1].title)
        assertEquals("John Writer", entries[1].author)
    }

    @Test
    fun `parseIndex ignores header and footer prose without urls`() {
        val entries = PgaBookSource.parseIndex(INDEX_FIXTURE)
        // No entry should ever be synthesised from the free-text header lines.
        assertTrue(entries.none { it.title.contains("List of FREE ebooks", ignoreCase = true) })
        assertTrue(entries.none { it.title.contains("T S Eliot", ignoreCase = true) })
    }

    @Test
    fun `search maps entries to prefixed books and filters by query`() = runBlocking {
        val source = PgaBookSource(fakeApi(INDEX_FIXTURE), context)

        val all = source.search("")
        assertEquals(2, all.size)

        val filtered = source.search("tale")
        assertEquals(1, filtered.size)
        assertEquals("pga_0100031", filtered[0].id)
        assertEquals("Another Tale", filtered[0].title)
        assertEquals("TXT", filtered[0].format)
        assertEquals("en", filtered[0].language)
    }

    @Test
    fun `downloadContent saves a txt file and strips the licence header`() = runBlocking {
        val source = PgaBookSource(fakeApi(INDEX_FIXTURE), context)
        val book = Book(id = "pga_2400261", title = "The Test Book", author = "Jane Author", format = "TXT")

        val downloaded = source.downloadContent(book)

        assertTrue(downloaded.isSaved)
        assertTrue(downloaded.filePath!!.endsWith(".txt"))
        val file = File(downloaded.filePath!!)
        assertTrue(file.exists())
        val text = file.readText()
        assertTrue(text.contains("The real body text begins here."))
        assertFalse(text.contains("Project Gutenberg Australia"))
        assertTrue(source.canDownload(book))
        assertTrue(source.owns(book))
    }

    @Test
    fun `stripHtml converts tags and entities to readable prose`() {
        val html = "<html><body><p>Hello &amp; welcome</p><script>bad()</script></body></html>"
        val text = PgaBookSource.stripHtml(html)
        assertTrue(text.contains("Hello & welcome"))
        assertFalse(text.contains("<"))
        assertFalse(text.contains("bad()"))
    }

    private companion object {
        const val INDEX_FIXTURE = """
            Project Gutenberg Australia
            List of FREE ebooks

            Sep 2024 The Test Book, by Jane Author                [240026xx.xxx] 4553A
            http://gutenberg.net.au/ebooks24/2400261.txt
            http://gutenberg.net.au/ebooks24/2400261h.html

            Aug 2001 Another Tale, by John Writer                 [010003xx.xxx] 0003A
            [Author: John Writer]
            http://gutenberg.net.au/ebooks01/0100031.txt
            http://gutenberg.net.au/ebooks01/0100031h.html

            What we call the beginning is often the end
            --T S Eliot
        """
    }
}
