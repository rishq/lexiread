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
import org.junit.Assert.assertFalse
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

    /**
     * A saved copy of the live search results, from 2026-01-12.
     *
     * The selectors in [AnnasArchiveConfig] were written against the Openlib
     * port, and a page that stops matching them yields no rows — which looks
     * exactly like "this source has nothing". A saved page turns a restyle
     * into a failing test.
     */
    private val liveSearchPage: String by lazy {
        javaClass.getResourceAsStream("/annas_search_page.html")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("annas_search_page.html fixture is missing")
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // --- URL ---------------------------------------------------------------

    @Test
    fun `buildSearchUrl sends every filter key the site expects`() {
        val url = AnnasArchiveBookSource.buildSearchUrl("sherlock holmes", 1).toHttpUrl()

        assertEquals("https", url.scheme)
        assertEquals("annas-archive.gl", url.host)
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
            "https://annas-archive.gl/md5/abc123",
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
        assertEquals("https://annas-archive.gl/covers/abc.jpg", pride.coverUrl)
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
        assertEquals("https://annas-archive.gl/covers/a.jpg", entries[0].coverUrl)
    }

    @Test
    fun `parseSearchPage treats unrecognised markup as no results`() {
        val entries = AnnasArchiveBookSource.parseSearchPage(
            "<html><body><p>Nothing to see here.</p></body></html>"
        )

        assertTrue(entries.isEmpty())
    }

    /**
     * The real search page, as it was served on 2026-01-12.
     *
     * The selectors in [AnnasArchiveConfig] were written against the Openlib
     * port, and a page that stops matching them returns no rows at all — which
     * looks exactly like "this source has nothing". The fixture is a saved
     * copy of the live results list, so a restyle breaks a test instead of the
     * catalogue.
     */
    @Test
    fun `parseSearchPage reads the live search page`() {
        val entries = AnnasArchiveBookSource.parseSearchPage(liveSearchPage)

        assertEquals("every row of the page must be parsed", 5, entries.size)

        val first = entries.first()
        assertEquals("2ca737f4dbc7cd4d0cf9b6019ca4c26c", first.md5)
        assertEquals("A Very Vampy Christmas: From Sugarplums and Scandal", first.title)
        // The byline is the title link's next sibling, and it only counts when
        // it points at another search.
        assertEquals("Kerrelyn Sparks", first.author)
        assertTrue("the publisher is the byline's next sibling", first.publisher != null)
        // The info line names the format, not the file extension: "EPUB".
        assertEquals(FormatKind.EPUB, first.format)
        assertTrue("the cover is on the row", first.coverUrl != null)
    }

    // --- record page (port of Openlib's `_bookInfoParser`) ------------------

    @Test
    fun `parseBookInfo reads the record page`() {
        val info = AnnasArchiveBookSource.parseBookInfo(
            DETAIL_PAGE,
            "https://annas-archive.gl/md5/abc123"
        )

        assertNotNull(info)
        assertEquals("abc123", info!!.md5)
        assertEquals("Pride and Prejudice", info.title)
        assertEquals("Jane Austen", info.author)
        assertEquals("Penguin Classics", info.publisher)
        assertEquals("https://annas-archive.gl/covers/big.jpg", info.coverUrl)
        assertEquals(FormatKind.EPUB, info.format)
        // The description is the sibling of the label that reads "Description".
        assertEquals("A novel of manners.", info.description)
        // The mirror link is the point of visiting the record page.
        assertEquals(
            "https://annas-archive.gl/slow_download/abc123/0/1234",
            info.mirrorUrl
        )
    }

    @Test
    fun `parseBookInfo returns null when the page is not a record`() {
        assertNull(AnnasArchiveBookSource.parseBookInfo("<html><body>nope</body></html>", "https://annas-archive.gl/md5/x"))
    }

    // --- mirror page (port of Openlib's WebView script) --------------------

    @Test
    fun `parseMirrorLinks reads the slow_download page`() {
        val links = AnnasArchiveBookSource.parseMirrorLinks(
            MIRROR_PAGE,
            "https://annas-archive.gl/slow_download/abc123/0/1234"
        )

        assertEquals(listOf("https://libgen.li/ads.php?md5=abc123"), links)
    }

    @Test
    fun `parseMirrorLinks falls back to the list of partner servers`() {
        val links = AnnasArchiveBookSource.parseMirrorLinks(
            PARTNER_PAGE,
            "https://annas-archive.gl/slow_download/abc123/0/1234"
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
            "https://annas-archive.gl/covers/x.jpg",
            com.lexiread.core.util.UrlValidator.requireTrustedImageUrl("https://annas-archive.gl/covers/x.jpg")
        )
        assertThrows(SecurityException::class.java) {
            com.lexiread.core.util.UrlValidator.requireTrustedImageUrl("https://zlib.bz/covers/x.jpg")
        }
    }

    @Test
    fun `the cover cdn annas links to is trusted for images only`() {
        // The live search page links covers to the Z-Library CDN, not to the
        // archive's own domains, so the cover host has to be allowed for the
        // cards to show one.
        assertEquals(
            "https://s3proxy.cdn-zlib.sk/covers299/x.png",
            com.lexiread.core.util.UrlValidator.requireTrustedImageUrl(
                "https://s3proxy.cdn-zlib.sk/covers299/x.png"
            )
        )
        // Images only: a book file must never be fetched from the CDN.
        assertThrows(SecurityException::class.java) {
            com.lexiread.core.util.UrlValidator.requireTrustedDownloadUrl(
                "https://s3proxy.cdn-zlib.sk/books/x.epub"
            )
        }
    }

    @Test
    fun `lapsed and squatted annas hosts are no longer trusted`() {
        // .org and .se stopped resolving in Jan 2026; .li was deleted in Mar 2026
        // and now serves a domain-sale page. Leaving any of them trusted would
        // mean fetching covers — and book files — from whoever holds the domain
        // today, which is the failure this whole change exists to close.
        listOf(
            "annas-archive.org",
            "annas-archive.se",
            "annas-archive.li",
            "annas-archive.pm",
            "annas-archive.gs"
        ).forEach { host ->
            assertThrows(
                "a lapsed domain must not be trusted: $host",
                SecurityException::class.java
            ) {
                com.lexiread.core.util.UrlValidator.requireTrustedImageUrl("https://$host/covers/x.jpg")
            }
        }
    }

    @Test
    fun `the compiled-in failover order is well formed`() {
        val hosts = AnnasArchiveConfig.DEFAULT_HOSTS

        assertTrue("at least one host is needed", hosts.isNotEmpty())
        assertEquals(AnnasArchiveConfig.DEFAULT_HOST, hosts.first())
        assertEquals("a repeated host would be fetched twice", hosts.size, hosts.distinct().size)
        hosts.forEach { host ->
            assertTrue("'$host' must not carry a scheme", !host.contains("://"))
            assertTrue("'$host' must not carry a path", !host.contains("/"))
            assertEquals("'$host' should be lowercase", host, host.lowercase())
        }
    }

    // --- host failover -----------------------------------------------------

    @Test
    fun `looksLikeArchive rejects the parking pages that took over the old domains`() {
        // Shapes taken from what those domains actually serve now.
        val aboveDomains = """
            <html><head><title>annas-archive.li</title></head><body>
              <script src="https://assets.abovedomains.com/javascript/forsale.min.js?d=annas-archive.li"></script>
              <h1>This domain is for sale</h1>
            </body></html>
        """
        val parkLogic =
            """<html><body><script>location.replace("https://router.parklogic.com/")</script></body></html>"""
        val adNetwork =
            """<html><body><a href="https://xml-v4.clickpathworks.com/click?i=abc">go</a></body></html>"""

        // The first one even names the archive — the parking marker must still win.
        assertTrue(
            "a domain-sale page must not pass as the archive",
            !AnnasArchiveBookSource.looksLikeArchive(aboveDomains)
        )
        assertTrue(
            "a ParkLogic page must not pass as the archive",
            !AnnasArchiveBookSource.looksLikeArchive(parkLogic)
        )
        assertTrue(
            "an ad hop must not pass as the archive",
            !AnnasArchiveBookSource.looksLikeArchive(adNetwork)
        )
    }

    @Test
    fun `looksLikeArchive rejects blank and unrelated pages`() {
        assertTrue(!AnnasArchiveBookSource.looksLikeArchive(""))
        assertTrue(!AnnasArchiveBookSource.looksLikeArchive("   "))
        assertTrue(!AnnasArchiveBookSource.looksLikeArchive("<html><body>Nothing to see here.</body></html>"))
    }

    @Test
    fun `looksLikeArchive accepts the real site`() {
        assertTrue(AnnasArchiveBookSource.looksLikeArchive(SEARCH_PAGE))
        assertTrue(AnnasArchiveBookSource.looksLikeArchive(DETAIL_PAGE))
    }

    @Test
    fun `search falls through to the next host when the first is unreachable`(): Unit = runBlocking {
        val seen = mutableListOf<String>()
        val api = object : AnnasArchiveApi {
            override suspend fun fetch(url: String): ResponseBody {
                seen += url
                if (url.contains(AnnasArchiveConfig.DEFAULT_HOST)) {
                    throw java.io.IOException("no route to host")
                }
                return body(SEARCH_PAGE)
            }
        }
        val source = AnnasArchiveBookSource(api, context)

        val entries = source.searchEntries("pride")

        assertEquals(2, entries.size)
        assertTrue(
            "the compiled-in host should be tried first",
            seen.first().contains(AnnasArchiveConfig.DEFAULT_HOST)
        )
        assertTrue(
            "a later host should have served the page",
            seen.any { !it.contains(AnnasArchiveConfig.DEFAULT_HOST) }
        )
    }

    @Test
    fun `search skips a host that serves a parking page`(): Unit = runBlocking {
        val parked = """
            <html><head><title>annas-archive.gl</title></head><body>
              <h1>This domain is for sale</h1>
            </body></html>
        """
        val api = object : AnnasArchiveApi {
            override suspend fun fetch(url: String): ResponseBody =
                if (url.contains(AnnasArchiveConfig.DEFAULT_HOST)) body(parked) else body(SEARCH_PAGE)
        }
        val source = AnnasArchiveBookSource(api, context)

        assertEquals(2, source.searchEntries("pride").size)
    }

    // --- browser check -----------------------------------------------------

    /**
     * The page the site answers a plain request with, as it was served on
     * 2026-10-20: HTTP 403, `Server: ddos-guard`, and a script that
     * fingerprints the browser before reloading the page.
     */
    private val DDG_CHALLENGE_PAGE = """
        <!doctype html><html><head><title>DDoS-Guard</title><meta charset="utf-8"/>
        <script defer="defer" src="/.well-known/ddos-guard/js-challenge/index.js"></script>
        <script src="https://check.ddos-guard.net/check.js"></script></head>
        <body data-ddg-origin="true"><div class="container">
        <h1 id="ddg-l10n-title">Checking your browser before accessing</h1>
        </div></body></html>
    """

    @Test
    fun `looksLikeChallenge recognises the ddos guard check page`() {
        assertTrue(AnnasArchiveBookSource.looksLikeChallenge(DDG_CHALLENGE_PAGE))
        assertTrue(AnnasArchiveBookSource.looksLikeChallenge("<title>Just a moment...</title>"))
        assertFalse(AnnasArchiveBookSource.looksLikeChallenge(SEARCH_PAGE))
        assertFalse(AnnasArchiveBookSource.looksLikeChallenge(""))
    }

    @Test
    fun `search raises the browser check when the site answers 403`(): Unit = runBlocking {
        // What the site actually does: a plain request gets a 403 and a
        // JavaScript check. Without this the source reports "no results" and
        // the user is left with an empty catalogue.
        val walled = object : AnnasArchiveApi {
            override suspend fun fetch(url: String): ResponseBody =
                throw retrofit2.HttpException(
                    retrofit2.Response.error<ResponseBody>(403, body(DDG_CHALLENGE_PAGE))
                )
        }
        val source = AnnasArchiveBookSource(walled, context)

        val error = runCatching { source.searchEntries("pride") }.exceptionOrNull()

        assertTrue(
            "the browser check must reach the UI, got $error",
            error is ChallengeRequiredException
        )
    }

    @Test
    fun `a check page served with 200 is still a check, not a stale host`(): Unit = runBlocking {
        // The same page can come back with 200; the markup is the signal.
        val api = object : AnnasArchiveApi {
            override suspend fun fetch(url: String): ResponseBody = body(DDG_CHALLENGE_PAGE)
        }
        val source = AnnasArchiveBookSource(api, context)

        val error = runCatching { source.searchEntries("pride") }.exceptionOrNull()

        assertTrue(
            "the browser check must reach the UI, got $error",
            error is ChallengeRequiredException
        )
    }

    @Test
    fun `the user's host override is tried before the compiled-in hosts`(): Unit = runBlocking {
        val seen = mutableListOf<String>()
        val api = object : AnnasArchiveApi {
            override suspend fun fetch(url: String): ResponseBody {
                seen += url
                return body(SEARCH_PAGE)
            }
        }
        val source = AnnasArchiveBookSource(
            api,
            context,
            hostOverride = { "mirror.example.org" }
        )

        source.searchEntries("pride")

        assertTrue(
            "the override should win over the compiled-in order, got ${seen.firstOrNull()}",
            seen.first().contains("mirror.example.org")
        )
    }

    @Test
    fun `an unusable host override is ignored rather than sent`(): Unit = runBlocking {
        val seen = mutableListOf<String>()
        val api = object : AnnasArchiveApi {
            override suspend fun fetch(url: String): ResponseBody {
                seen += url
                return body(SEARCH_PAGE)
            }
        }
        val source = AnnasArchiveBookSource(
            api,
            context,
            // Not a hostname: the URL builder would throw, or worse, fetch it.
            hostOverride = { "javascript:alert(1)" }
        )

        source.searchEntries("pride")

        assertTrue(
            "a malformed override must not reach the network, got ${seen.firstOrNull()}",
            seen.none { it.contains("javascript") }
        )
        assertTrue(seen.first().contains(AnnasArchiveConfig.DEFAULT_HOST))
    }

    @Test
    fun `sanitizeHost reduces user input to a bare hostname`() {
        val source = AnnasArchiveBookSource(fakeApi(), context)

        assertEquals("annas-archive.gl", source.sanitizeHost("annas-archive.gl"))
        assertEquals("annas-archive.gl", source.sanitizeHost("  ANNAS-ARCHIVE.GL  "))
        assertEquals("annas-archive.gl", source.sanitizeHost("https://annas-archive.gl/"))
        assertEquals("annas-archive.gl", source.sanitizeHost("annas-archive.gl/search?q=x"))
        assertEquals("annas-archive.gl", source.sanitizeHost("annas-archive.gl:8443"))
        assertEquals("annas-archive.gl", source.sanitizeHost("annas-archive.gl."))
    }

    @Test
    fun `sanitizeHost rejects anything that is not a hostname`() {
        val source = AnnasArchiveBookSource(fakeApi(), context)

        assertNull(source.sanitizeHost(null))
        assertNull(source.sanitizeHost(""))
        assertNull(source.sanitizeHost("   "))
        assertNull(source.sanitizeHost("javascript:alert(1)"))
        assertNull(source.sanitizeHost("not a host"))
        assertNull(source.sanitizeHost("localhost"))
        assertNull(source.sanitizeHost("-leading.example.com"))
        assertNull(source.sanitizeHost("a".repeat(300) + ".com"))
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
            <html><head><title>Anna's Archive</title></head><body>
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
            <html><head><title>Anna's Archive</title></head><body>
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
