package com.lexiread.data.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lexiread.data.remote.api.AnnasArchiveApi
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.FormatKind
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
 * The parsers in this source are a port of the Openlib app's
 * (`lib/services/annas_archieve.dart` and `lib/ui/webview_page.dart`), so the
 * fixtures are shaped like the markup those selectors were written against.
 *
 * Each parser is exercised on its own, against saved HTML, before anything
 * touches the network — a scraper rots from two directions at once (the site
 * restyles, and the fixture stops resembling the live page), and a pure parser
 * is the only part that can be pinned down.
 *
 * Robolectric because the source takes a [Context] for its download directory
 * and logs during parsing; the parsers themselves are pure JVM.
 */
@RunWith(RobolectricTestRunner::class)
class AnnasArchiveBookSourceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // --- URL ---------------------------------------------------------------

    @Test
    fun `buildSearchUrl sends every filter key the site expects`() {
        val url = AnnasArchiveBookSource.buildSearchUrl("sherlock holmes", 1).toHttpUrl()

        assertEquals("https", url.scheme)
        assertEquals("annas-archive.se", url.host)
        assertEquals("/search", url.encodedPath)
        assertEquals("sherlock holmes", url.queryParameter("q"))
        // Openlib sends these even when empty; the site's form expects the keys.
        assertEquals("", url.queryParameter("index"))
        assertEquals("", url.queryParameter("content"))
        assertEquals("", url.queryParameter("ext"))
        assertEquals("", url.queryParameter("sort"))
    }

    @Test
    fun `buildSearchUrl omits the page parameter on the first page`() {
        assertNull(AnnasArchiveBookSource.buildSearchUrl("sherlock", 1).toHttpUrl().queryParameter("page"))
        assertEquals("3", AnnasArchiveBookSource.buildSearchUrl("sherlock", 3).toHttpUrl().queryParameter("page"))
    }

    @Test
    fun `buildSearchUrl percent-encodes a non-ascii query`() {
        val url = AnnasArchiveBookSource.buildSearchUrl("Преступление и наказание", 1).toHttpUrl()

        assertEquals("Преступление и наказание", url.queryParameter("q"))
        assertTrue(
            "Cyrillic titles must be percent-encoded, not sent raw",
            url.encodedQuery.orEmpty().contains("%D0%9F")
        )
    }

    @Test
    fun `recordUrl points at the md5 path`() {
        assertEquals(
            "https://annas-archive.se/md5/abc123",
            AnnasArchiveBookSource.recordUrl("abc123")
        )
    }

    // --- search page (port of Openlib's `_parser`) -------------------------

    @Test
    fun `parseSearchPage reads a row through the ported selectors`() {
        val entries = AnnasArchiveBookSource.parseSearchPage(SEARCH_PAGE)

        assertEquals(2, entries.size)
        val pride = entries[0]
        assertEquals("abc123", pride.md5)
        assertEquals("Pride and Prejudice", pride.title)
        // Author and publisher come from the title link's next two siblings.
        assertEquals("Jane Austen", pride.author)
        assertEquals("Penguin Classics", pride.publisher)
        assertEquals("https://annas-archive.se/covers/abc.jpg", pride.coverUrl)
        assertEquals("English [en], .epub, 1.2MB, 1813", pride.info)
        assertEquals(FormatKind.EPUB, pride.format)
    }

    @Test
    fun `parseSearchPage maps a pdf info line to the pdf format`() {
        val entries = AnnasArchiveBookSource.parseSearchPage(SEARCH_PAGE)

        assertEquals("def456", entries[1].md5)
        assertEquals(FormatKind.PDF, entries[1].format)
    }

    @Test
    fun `parseSearchPage ignores a sibling that is not a search link`() {
        // The anchor after the title points somewhere else entirely; it is
        // navigation, not a byline, so it must not be read as the author.
        val entries = AnnasArchiveBookSource.parseSearchPage(UNRELATED_SIBLING_PAGE)

        assertEquals(1, entries.size)
        assertNull(entries[0].author)
        assertNull(entries[0].publisher)
    }

    @Test
    fun `parseSearchPage strips the icon token from an author label`() {
        // Some layouts render the byline as "icon-user Jane Austen".
        val entries = AnnasArchiveBookSource.parseSearchPage(ICON_AUTHOR_PAGE)

        assertEquals("Jane Austen", entries[0].author)
    }

    @Test
    fun `parseSearchPage drops rows that advertise no readable format`() {
        val entries = AnnasArchiveBookSource.parseSearchPage(
            SEARCH_PAGE.replace("English [en], .epub, 1.2MB, 1813", "archived scan, unknown")
        )

        // Only the PDF row survives the format filter.
        assertEquals(listOf("def456"), entries.map { it.md5 })
    }

    @Test
    fun `parseSearchPage keeps rows when the filter would empty the page`() {
        // A restyled info line must not look like "no results at all".
        val html = SEARCH_PAGE.replace("English [en], .epub, 1.2MB, 1813", "archived scan, unknown")
            .replace("English [en], .pdf, 8.4MB, 1851", "also unknown")

        val entries = AnnasArchiveBookSource.parseSearchPage(html)

        assertEquals(2, entries.size)
        assertTrue(entries.all { it.format == null })
    }

    @Test
    fun `parseSearchPage finds rows without the configured row class`() {
        val entries = AnnasArchiveBookSource.parseSearchPage(UNSTYLED_PAGE)

        assertEquals(2, entries.size)
        assertEquals("AAA111", entries[0].md5)
        assertEquals("Some Book Title", entries[0].title)
        assertEquals("Some Author", entries[0].author)
        assertEquals("https://annas-archive.se/covers/a.jpg", entries[0].coverUrl)
    }

    @Test
    fun `parseSearchPage treats unrecognised markup as no results`() {
        val entries = AnnasArchiveBookSource.parseSearchPage(
            "<html><body><p>Nothing to see here.</p></body></html>"
        )

        assertTrue(entries.isEmpty())
    }

    // --- record page (port of Openlib's `_bookInfoParser`) ------------------

    @Test
    fun `parseBookInfo reads the record page`() {
        val info = AnnasArchiveBookSource.parseBookInfo(
            DETAIL_PAGE,
            "https://annas-archive.se/md5/abc123"
        )

        assertNotNull(info)
        assertEquals("abc123", info!!.md5)
        assertEquals("Pride and Prejudice", info.title)
        assertEquals("Jane Austen", info.author)
        assertEquals("Penguin Classics", info.publisher)
        assertEquals("https://annas-archive.se/covers/big.jpg", info.coverUrl)
        assertEquals(FormatKind.EPUB, info.format)
        // The description is the sibling of the label that reads "Description".
        assertEquals("A novel of manners.", info.description)
        // The mirror link is the point of visiting the record page.
        assertEquals(
            "https://annas-archive.se/slow_download/abc123/0/1234",
            info.mirrorUrl
        )
    }

    @Test
    fun `parseBookInfo returns null when the page is not a record`() {
        assertNull(AnnasArchiveBookSource.parseBookInfo("<html><body>nope</body></html>", "https://annas-archive.se/md5/x"))
    }

    // --- mirror page (port of Openlib's WebView script) --------------------

    @Test
    fun `parseMirrorLinks reads the slow_download page`() {
        val links = AnnasArchiveBookSource.parseMirrorLinks(
            MIRROR_PAGE,
            "https://annas-archive.se/slow_download/abc123/0/1234"
        )

        assertEquals(listOf("https://libgen.li/ads.php?md5=abc123"), links)
    }

    @Test
    fun `parseMirrorLinks falls back to the list of partner servers`() {
        val links = AnnasArchiveBookSource.parseMirrorLinks(
            PARTNER_PAGE,
            "https://annas-archive.se/slow_download/abc123/0/1234"
        )

        assertEquals(
            listOf("https://libgen.li/get/abc123", "https://ipfs.io/ipfs/Qm123"),
            links
        )
    }

    // --- format ------------------------------------------------------------

    @Test
    fun `formatFrom maps the containers the reader can open`() {
        assertEquals(FormatKind.PDF, AnnasArchiveBookSource.formatFrom("English [en], .pdf, 8MB"))
        assertEquals(FormatKind.EPUB, AnnasArchiveBookSource.formatFrom("English [en], .epub, 1MB"))
        assertNull(AnnasArchiveBookSource.formatFrom("English [en], .cbr, 40MB"))
        assertNull(AnnasArchiveBookSource.formatFrom("English [en], .cbz, 40MB"))
        assertNull(AnnasArchiveBookSource.formatFrom("no format named here"))
        assertNull(AnnasArchiveBookSource.formatFrom(null))
    }

    // --- source ------------------------------------------------------------

    @Test
    fun `search maps rows to prefixed books`() = runBlocking {
        val source = AnnasArchiveBookSource(fakeApi(), context)

        val books = source.search("pride")

        assertEquals(2, books.size)
        assertEquals("annas_abc123", books[0].id)
        assertEquals("Jane Austen", books[0].author)
        assertEquals("EPUB", books[0].format)
        assertTrue(source.owns(books[0]))
        assertTrue(source.canDownload(books[0]))
    }

    @Test
    fun `a blank query never hits the network`() = runBlocking {
        // An empty `q` asks the site for its whole catalogue.
        val source = AnnasArchiveBookSource(object : AnnasArchiveApi {
            override suspend fun fetch(url: String): ResponseBody =
                throw AssertionError("A blank query must not be sent: $url")
        }, context)

        assertTrue(source.search("   ").isEmpty())
    }

    @Test
    fun `downloadContent walks record page then mirror then file`() = runBlocking {
        val source = AnnasArchiveBookSource(fakeApi(), context)

        val downloaded = source.downloadContent(
            Book(id = "annas_abc123", title = "Pride and Prejudice", author = "Jane Austen")
        )

        assertTrue(downloaded.isSaved)
        assertEquals("EPUB", downloaded.format)
        val file = File(checkNotNull(downloaded.filePath))
        assertTrue(file.exists())
        assertTrue(file.name.endsWith(".epub"))
    }

    @Test
    fun `downloadContent refuses a mirror outside the allow-list`() {
        val api = object : AnnasArchiveApi {
            override suspend fun fetch(url: String): ResponseBody = when {
                url.contains("/md5/") -> body(DETAIL_PAGE)
                url.contains("/slow_download/") -> body(EVIL_MIRROR_PAGE)
                else -> throw AssertionError("Must not fetch the file from $url")
            }
        }
        val source = AnnasArchiveBookSource(api, context)

        // Block body on purpose: a `= runBlocking { assertThrows(...) }` test
        // returns the exception and JUnit fails the whole class.
        assertThrows(SecurityException::class.java) {
            runBlocking {
                source.downloadContent(Book(id = "annas_abc123", title = "x", author = "y"))
            }
        }
    }

    @Test(expected = ChallengeRequiredException::class)
    fun `downloadContent raises the browser challenge on 503`(): Unit = runBlocking {
        val walled = object : AnnasArchiveApi {
            override suspend fun fetch(url: String): ResponseBody =
                throw retrofit2.HttpException(
                    retrofit2.Response.error<ResponseBody>(
                        503,
                        body("Checking your browser ...")
                    )
                )
        }
        val source = AnnasArchiveBookSource(walled, context)

        source.downloadContent(Book(id = "annas_abc123", title = "x", author = "y"))
    }

    @Test
    fun `annas covers are on the global image allow-list, zlib is not`() {
        assertEquals(
            "https://annas-archive.se/covers/x.jpg",
            com.lexiread.core.util.UrlValidator.requireTrustedImageUrl("https://annas-archive.se/covers/x.jpg")
        )
        assertThrows(SecurityException::class.java) {
            com.lexiread.core.util.UrlValidator.requireTrustedImageUrl("https://zlib.bz/covers/x.jpg")
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun body(content: String): ResponseBody =
        ResponseBody.create("text/html".toMediaType(), content)

    private fun fakeApi(): AnnasArchiveApi = object : AnnasArchiveApi {
        override suspend fun fetch(url: String): ResponseBody = when {
            url.contains("/md5/") -> body(DETAIL_PAGE)
            url.contains("/slow_download/") -> body(MIRROR_PAGE)
            url.contains("libgen.li") -> ResponseBody.create(
                "application/epub+zip".toMediaType(), tinyEpub()
            )

            else -> body(SEARCH_PAGE)
        }
    }

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
        /** Openlib's row markup: `div.flex.pt-3.pb-3.border-b`. */
        const val SEARCH_PAGE = """
            <html><body>
              <div class="flex pt-3 pb-3 border-b">
                <div class="shrink-0">
                  <a href="/md5/abc123"><img src="/covers/abc.jpg"></a>
                </div>
                <div class="min-w-0">
                  <a href="/md5/abc123" class="line-clamp-[3] js-vim-focus">Pride and Prejudice</a>
                  <a href="/search?q=Jane+Austen">Jane Austen</a>
                  <a href="/search?q=Penguin+Classics">Penguin Classics</a>
                  <div class="text-gray-800">English [en], .epub, 1.2MB, 1813</div>
                </div>
              </div>
              <div class="flex pt-3 pb-3 border-b">
                <div class="shrink-0">
                  <a href="/md5/def456"><img src="/covers/def.jpg"></a>
                </div>
                <div class="min-w-0">
                  <a href="/md5/def456" class="line-clamp-[3] js-vim-focus">Moby Dick</a>
                  <a href="/search?q=Herman+Melville">Herman Melville</a>
                  <a href="/search?q=Harper">Harper</a>
                  <div class="text-gray-800">English [en], .pdf, 8.4MB, 1851</div>
                </div>
              </div>
            </body></html>
        """

        /** The sibling after the title is not a search link. */
        const val UNRELATED_SIBLING_PAGE = """
            <html><body>
              <div class="flex pt-3 pb-3 border-b">
                <a href="/md5/abc123" class="line-clamp-[3] js-vim-focus">Pride and Prejudice</a>
                <a href="https://example.com/about">About us</a>
                <div class="text-gray-800">English [en], .epub, 1.2MB</div>
              </div>
            </body></html>
        """

        /** Some layouts prefix the byline with an icon glyph's text node. */
        const val ICON_AUTHOR_PAGE = """
            <html><body>
              <div class="flex pt-3 pb-3 border-b">
                <a href="/md5/abc123" class="line-clamp-[3] js-vim-focus">Pride and Prejudice</a>
                <a href="/search?q=Jane+Austen">icon-user Jane Austen</a>
                <div class="text-gray-800">English [en], .epub, 1.2MB</div>
              </div>
            </body></html>
        """

        /** No configured row class, no configured title class — structure only. */
        const val UNSTYLED_PAGE = """
            <html><body>
              <div id="results">
                <div class="row">
                  <a href="/md5/AAA111"><img src="/covers/a.jpg"></a>
                  <a href="/md5/AAA111">Some Book Title</a>
                  <a href="/search?q=Some+Author">Some Author</a>
                  <div class="text-gray-800">English [en], .epub, 1MB</div>
                </div>
                <div class="row">
                  <a href="/md5/BBB222"><img src="/covers/b.jpg"></a>
                  <a href="/md5/BBB222">Another Book Title</a>
                  <a href="/search?q=Another+Author">Another Author</a>
                  <div class="text-gray-800">English [en], .epub, 1MB</div>
                </div>
              </div>
            </body></html>
        """

        /** Openlib's record markup: `div.main-inner` + `div.js-md5-top-box-description`. */
        const val DETAIL_PAGE = """
            <html><body>
              <div class="main-inner">
                <div id="list_cover_abc123"><img src="/covers/big.jpg"></div>
                <div class="font-semibold text-2xl">Pride and Prejudice</div>
                <a href="/search?q=Jane+Austen" class="text-base">Jane Austen</a>
                <a href="/search?q=Penguin+Classics" class="text-sm">Penguin Classics</a>
                <div class="text-gray-800">English [en], .epub, 1.2MB, 1813</div>
                <div class="js-md5-top-box-description">
                  <div class="text-xs text-gray-500 uppercase">Description</div>
                  <div class="mb-4">A novel of manners.</div>
                </div>
                <ul class="list-inside">
                  <li><a href="/slow_download/abc123/0/1234">Slow Partner Server #1</a></li>
                </ul>
              </div>
            </body></html>
        """

        /** The `/slow_download/` page: one bold paragraph holding the link. */
        const val MIRROR_PAGE = """
            <html><body>
              <p class="mb-4 text-xl font-bold">
                <a href="https://libgen.li/ads.php?md5=abc123">Slow Partner Server #1</a>
              </p>
            </body></html>
        """

        /** A partner page: the links are a plain list. */
        const val PARTNER_PAGE = """
            <html><body>
              <ul>
                <li><a href="https://libgen.li/get/abc123">Libgen</a></li>
                <li><a href="https://ipfs.io/ipfs/Qm123">IPFS</a></li>
              </ul>
            </body></html>
        """

        /** A mirror page advertising a host the app does not trust. */
        const val EVIL_MIRROR_PAGE = """
            <html><body>
              <p class="mb-4 text-xl font-bold">
                <a href="https://evil.example.com/abc123.epub">Slow Partner Server #1</a>
              </p>
            </body></html>
        """
    }
}
