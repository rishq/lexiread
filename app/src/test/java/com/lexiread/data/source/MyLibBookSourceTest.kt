package com.lexiread.data.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lexiread.data.remote.api.MyLibApi
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.BookFormat
import com.lexiread.domain.model.FormatKind
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Scrapers rot from two directions — the site restyles, and a fixture stops
 * looking like the live page. The parser is therefore tested on its own,
 * against saved HTML, before anything exercises the network path.
 *
 * Robolectric because `MyLibBookSource` takes a [Context] for its download
 * directory; the parser itself is pure JVM.
 */
@RunWith(RobolectricTestRunner::class)
class MyLibBookSourceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun fakeApi(html: String = SEARCH_PAGE): MyLibApi = object : MyLibApi {
        override suspend fun fetch(url: String): ResponseBody = when {
            url.endsWith(".epub") -> ResponseBody.create(
                "application/epub+zip".toMediaType(), tinyEpub()
            )

            else -> ResponseBody.create("text/html".toMediaType(), html)
        }
    }

    // --- URL ---------------------------------------------------------------

    @Test
    fun `buildSearchUrl sends every filter the site expects`() {
        val url = MyLibBookSource.buildSearchUrl("sherlock holmes", 3, MyLibConfig()).toHttpUrl()

        assertEquals("https", url.scheme)
        assertEquals("zlib.bz", url.host)
        // The site's search route is `/s/`, not `/s`.
        assertEquals("/s/", url.encodedPath)
        assertEquals("sherlock holmes", url.queryParameter("q"))
        assertEquals(listOf("english"), url.queryParameterValues("languages[]"))
        assertEquals(listOf("EPUB", "TXT", "FB2", "PDF"), url.queryParameterValues("extensions[]"))
        assertEquals(listOf("book"), url.queryParameterValues("selected_content_types[]"))
        assertEquals("3", url.queryParameter("page"))
    }

    @Test
    fun `buildSearchUrl omits the page parameter on the first page`() {
        val url = MyLibBookSource.buildSearchUrl("sherlock", 1).toHttpUrl()
        assertNull(url.queryParameter("page"))
    }

    @Test
    fun `buildSearchUrl encodes a non-ascii query`() {
        val url = MyLibBookSource.buildSearchUrl("Преступление и наказание", 1).toHttpUrl()
        assertEquals("Преступление и наказание", url.queryParameter("q"))
        assertTrue(
            "Russian titles must be percent-encoded, not sent raw",
            url.encodedQuery.orEmpty().contains("%D0%9F")
        )
    }

    // --- parsing -----------------------------------------------------------

    @Test
    fun `parseSearchPage reads a row through the configured selectors`() {
        val page = MyLibBookSource.parseSearchPage(SEARCH_PAGE, BASE_URL)

        assertEquals(2, page.entries.size)
        val pride = page.entries[0]
        assertEquals("12345", pride.id)
        assertEquals("Pride and Prejudice", pride.title)
        assertEquals("Jane Austen", pride.author)
        // Relative cover paths must survive as absolute URLs.
        assertEquals("https://zlib.bz/covers/abc.jpg", pride.coverUrl)
        assertEquals("https://zlib.bz/book/12345/pride-and-prejudice", pride.detailUrl)
        assertEquals(listOf(FormatKind.EPUB, FormatKind.TXT), pride.formats.map { it.kind })
        assertEquals("https://zlib.bz/download/12345.epub", pride.formats[0].url)
        assertEquals(1813, pride.year)
        assertEquals("English", pride.language)
    }

    @Test
    fun `parseSearchPage offers only formats the reader can open`() {
        val page = MyLibBookSource.parseSearchPage(SEARCH_PAGE, BASE_URL)

        // Moby Dick is published only as FB2 and PDF here. Advertising them
        // would produce a card that looks readable and fails on tap.
        val moby = page.entries[1]
        assertEquals("67890", moby.id)
        assertTrue(moby.formats.isEmpty())
        assertTrue(moby.formats.none { it.kind == FormatKind.EPUB })
    }

    @Test
    fun `parseSearchPage finds rows without any configured selector`() {
        // No class in this fixture matches MyLibSelectors, so the parser has to
        // detect the repeated structure: two sibling rows, each with a book link.
        val page = MyLibBookSource.parseSearchPage(UNSTYLED_PAGE, BASE_URL)

        assertEquals(2, page.entries.size)
        assertEquals("AAA111", page.entries[0].id)
        assertEquals("Some Book Title", page.entries[0].title)
        // No `.author` element here, so the "Author: …" label is the only clue.
        assertEquals("Some Author", page.entries[0].author)
        assertEquals(listOf(FormatKind.EPUB), page.entries[0].formats.map { it.kind })
        assertNull(page.entries[0].coverUrl)
    }

    @Test
    fun `parseSearchPage reads the result count and the next-page link`() {
        val page = MyLibBookSource.parseSearchPage(SEARCH_PAGE, BASE_URL, requestedPage = 1)

        assertEquals(431, page.totalResults)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `parseSearchPage stops paginating on the last page`() {
        val page = MyLibBookSource.parseSearchPage(LAST_PAGE, BASE_URL, requestedPage = 2)

        assertEquals(12, page.totalResults)
        assertTrue(
            "A pagination block with no forward link means the end",
            !page.hasNextPage
        )
    }

    @Test
    fun `parseSearchPage treats unrecognised markup as no results`() {
        val page = MyLibBookSource.parseSearchPage(
            "<html><body><p>Nothing to see here.</p></body></html>", BASE_URL
        )
        assertTrue(page.entries.isEmpty())
        assertNull(page.totalResults)
        assertTrue(!page.hasNextPage)
    }

    // --- source ------------------------------------------------------------

    @Test
    fun `search maps rows to prefixed books`() = runBlocking {
        val source = MyLibBookSource(fakeApi(), context)

        val books = source.search("pride")

        assertEquals(2, books.size)
        assertEquals("mylib_12345", books[0].id)
        assertEquals("Jane Austen", books[0].author)
        assertEquals("EPUB", books[0].format)
        assertTrue(source.owns(books[0]))
        assertTrue(source.canDownload(books[0]))
    }

    @Test
    fun `a blank query never hits the network`() = runBlocking {
        // An empty `q` asks the site for its entire catalogue.
        val source = MyLibBookSource(object : MyLibApi {
            override suspend fun fetch(url: String): ResponseBody =
                throw AssertionError("A blank query must not be sent: $url")
        }, context)

        assertTrue(source.search("   ").isEmpty())
    }

    @Test
    fun `downloadContent writes the best format to disk`() = runBlocking {
        val source = MyLibBookSource(fakeApi(), context)
        // What a real search would have remembered before the user tapped.
        source.remember(MyLibBookSource.parseSearchPage(SEARCH_PAGE, BASE_URL).entries)

        val downloaded = source.downloadContent(
            Book(id = "mylib_12345", title = "Pride and Prejudice", author = "Jane Austen")
        )

        assertTrue(downloaded.isSaved)
        assertEquals("EPUB", downloaded.format)
        val file = File(checkNotNull(downloaded.filePath))
        assertTrue(file.exists())
        assertTrue(file.name.endsWith(".epub"))
    }

    @Test(expected = SecurityException::class)
    fun `downloadContent refuses a host outside the allow-list`(): Unit = runBlocking {
        val source = MyLibBookSource(fakeApi(), context)
        source.remember(
            listOf(
                MyLibEntry(
                    id = "evil",
                    title = "Evil",
                    author = null,
                    coverUrl = null,
                    detailUrl = "https://zlib.bz/book/evil",
                    formats = listOf(
                        BookFormat(FormatKind.EPUB, "application/epub+zip", "https://evil.example.com/b.epub")
                    ),
                    language = null,
                    year = null,
                    description = null
                )
            )
        )

        source.downloadContent(Book(id = "mylib_evil", title = "Evil", author = "?"))
    }

    // --- fixtures -----------------------------------------------------------

    /** The smallest file that still passes SafeDownloader's EPUB check. */
    private fun tinyEpub(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("<container/>".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private companion object {
        const val BASE_URL = "https://zlib.bz/s/?q=test"

        const val SEARCH_PAGE = """
            <html><body>
              <div class="book-item">
                <img class="cover" src="/covers/abc.jpg">
                <h3 class="title"><a href="/book/12345/pride-and-prejudice">Pride and Prejudice</a></h3>
                <span class="author">Jane Austen</span>
                <div class="meta"><span>Year: 1813</span><span>Language: English</span></div>
                <a href="/download/12345.epub">EPUB</a>
                <a href="/download/12345.txt">TXT</a>
              </div>
              <div class="book-item">
                <img data-src="/covers/def.jpg">
                <h3 class="title"><a href="/book/67890/moby-dick">Moby Dick</a></h3>
                <span class="author">Herman Melville</span>
                <div class="meta"><span>Year: 1851</span><span>Language: English</span></div>
                <a href="/download/67890.fb2">FB2</a>
                <a href="/download/67890.pdf">PDF</a>
              </div>
              <div class="pagination">
                <span>Results 1-2 of 431</span>
                <a href="/s/?q=test&amp;page=2">Next &rsaquo;</a>
              </div>
            </body></html>
        """

        /** Same shape, none of the class names the config knows about. */
        const val UNSTYLED_PAGE = """
            <html><body>
              <div id="records">
                <div>
                  <a href="/md5/AAA111">Some Book Title</a>
                  <span>Author: Some Author</span>
                  <a href="/get/AAA111.epub">EPUB</a>
                </div>
                <div>
                  <a href="/md5/BBB222">Another Book Title</a>
                  <span>Author: Another Author</span>
                  <a href="/get/BBB222.epub">EPUB</a>
                </div>
              </div>
              <div class="pager"><a href="/s/?q=test&amp;page=2">2</a></div>
            </body></html>
        """

        const val LAST_PAGE = """
            <html><body>
              <div class="book-item">
                <img class="cover" src="/covers/abc.jpg">
                <h3 class="title"><a href="/book/12345/pride-and-prejudice">Pride and Prejudice</a></h3>
                <span class="author">Jane Austen</span>
                <a href="/download/12345.epub">EPUB</a>
              </div>
              <div class="pagination">
                <span>Results 11-12 of 12</span>
                <a href="/s/?q=test&amp;page=1">Previous</a>
              </div>
            </body></html>
        """
    }
}
