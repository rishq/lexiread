package com.lexiread.data.source

import android.content.Context
import android.util.Log
import com.lexiread.core.util.BookFormatSelector
import com.lexiread.core.util.SafeDownloader
import com.lexiread.core.util.TextEncoding
import com.lexiread.core.util.UrlValidator
import com.lexiread.core.util.forceHttps
import com.lexiread.data.remote.api.AnnasArchiveApi
import com.lexiread.domain.model.Author
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.BookFormat
import com.lexiread.domain.model.BookIdentifiers
import com.lexiread.domain.model.CatalogBook
import com.lexiread.domain.model.FormatKind
import com.lexiread.domain.model.SourceKind
import com.lexiread.domain.repository.BookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.util.Locale

/**
 * Anna's Archive, scraped — the same approach the Openlib app uses, ported to
 * Jsoup. There is no API: the site is plain HTML, so every value comes from
 * markup and the selectors are the contract.
 *
 * The three pages this source reads, in the order the download walks them:
 *
 * 1. **Search** — `https://annas-archive.se/search?index=&q=…&content=&ext=&sort=`.
 *    Rows are `div.flex.pt-3.pb-3.border-b`. The title anchor is
 *    `a.line-clamp-[3].js-vim-focus`; author and publisher are its *next two
 *    siblings* (not `:nth-of-type` — that was the bug Openlib fixed), and only
 *    when those siblings point at `/search?q=`. The info line is
 *    `div.text-gray-800`, and the file's md5 is the last path segment of the
 *    `/md5/…` href.
 * 2. **Detail** — `/md5/<md5>`. `div.main-inner` holds the title
 *    (`div.font-semibold.text-2xl`), the cover (`div[id^="list_cover_"] img`),
 *    the description (the sibling of the `… .uppercase` label inside
 *    `div.js-md5-top-box-description`), and — the point of the visit — the
 *    mirror link (`ul.list-inside a[href*="/slow_download/"]`).
 * 3. **Mirror** — the `/slow_download/…` page, which lists the partner servers
 *    that actually serve the file.
 *
 * Two rules are inherited from the rest of the app rather than from Openlib:
 *
 * - **Jsoup parses, it does not fetch.** The page is fetched through the app's
 *   Retrofit client so timeouts, cookies and the redirect guard are shared with
 *   every other catalogue. `Jsoup.connect()` would open a second, unmanaged
 *   HTTP stack.
 * - **A failure is a failure.** Exceptions propagate so
 *   `BooksRepositoryImpl` can isolate them per source and name them in
 *   `CatalogPage.failedSources`.
 *
 * ## The browser check
 *
 * Anna's Archive sits behind Cloudflare. When a fetch comes back 503 the source
 * throws [ChallengeRequiredException] — the same signal `MyLibBookSource` uses —
 * and the UI opens [com.lexiread.presentation.common.ChallengeWebViewDialog].
 * The WebView clears the check, the clearance cookie is copied into the shared
 * cookie jar, and the download is retried with the session attached.
 */
class AnnasArchiveBookSource(
    private val api: AnnasArchiveApi,
    context: Context,
    /**
     * Host, routes and selectors. Public so the repository can build the same
     * record-page URLs the parser used; there is one instance of this source and
     * one config behind it.
     */
    val config: AnnasArchiveConfig = AnnasArchiveConfig()
) : BookSource {

    override val idPrefix = ID_PREFIX
    override val displayName = "Anna's Archive"

    private val booksDir = File(context.applicationContext.filesDir, DOWNLOAD_DIR).apply { mkdirs() }

    override suspend fun search(query: String): List<Book> = withContext(Dispatchers.IO) {
        searchEntries(query, FIRST_PAGE).map { it.toBook() }
    }

    /**
     * One page of parsed rows, for the repository to map into `CatalogBook`s.
     *
     * The repository owns the domain mapping for every source (see
     * `BooksRepositoryImpl.fetchMyLib`), so this exposes the parse result rather
     * than a `CatalogBook` list. Fetching and parsing stay here, where the
     * selectors and the challenge handling live.
     */
    internal suspend fun searchEntries(query: String, page: Int = FIRST_PAGE): List<AnnasArchiveEntry> =
        withContext(Dispatchers.IO) {
            val normalized = query.trim()
            // An empty `q` asks the site for its whole catalogue; that is not a search.
            if (normalized.isBlank()) return@withContext emptyList()

            val html = fetchHtml(buildSearchUrl(normalized, page, config))
            parseSearchPage(html, config).take(SEARCH_LIMIT)
        }

    override fun canDownload(book: Book): Boolean = owns(book)

    override suspend fun downloadContent(book: Book): Book = withContext(Dispatchers.IO) {
        val md5 = book.id.removePrefix("${ID_PREFIX}_")
        require(md5.isNotBlank()) { "Not an Anna's Archive id: ${book.id}" }

        // 1. The detail page carries the mirror link. It is derived from the md5
        //    rather than remembered from search, so a book restored from the
        //    library still downloads after a process restart.
        val detailUrl = detailUrlFor(md5)
        val info = parseBookInfo(fetchHtml(detailUrl), detailUrl, config)
            ?: throw IllegalStateException("Anna's Archive returned no record for '${book.title}'.")

        val mirrorPage = info.mirrorUrl
            ?: throw UnsupportedOperationException(
                "No download mirror is listed for '${book.title}'."
            )

        val format = info.format
            ?: throw UnsupportedOperationException(
                "Anna's Archive lists '${book.title}' only in a format this reader cannot open."
            )

        // 2. The mirror page lists the servers that actually hold the file.
        val candidates = parseMirrorLinks(fetchHtml(mirrorPage), mirrorPage, config)
        require(candidates.isNotEmpty()) {
            "No download server is listed for '${book.title}'."
        }

        // 3. First server that yields a valid file wins; the rest are fallbacks.
        val destination = File(booksDir, "${ID_PREFIX}_${safeStem(md5)}.${BookFormatSelector.fileExtension(format)}")
        var lastError: Throwable? = null
        for (candidate in candidates) {
            val url = try {
                trustedDownloadUrl(candidate)
            } catch (error: Throwable) {
                // Not on the allow-list. Skip it rather than abort: one bad link
                // in the page must not cost the user the whole download.
                lastError = error
                continue
            }
            try {
                api.fetch(url).use { body ->
                    SafeDownloader.downloadToFile(body, destination, MAX_DOWNLOAD_BYTES)
                }
                requireValidDownload(destination, format, book.title, url)
                return@withContext book.copy(
                    filePath = destination.absolutePath,
                    format = format.name,
                    isSaved = true
                )
            } catch (error: Throwable) {
                lastError = error
                runCatching { destination.delete() }
                // A challenge is worth surfacing: the user clears it once in the
                // WebView and the retry walks the same list again.
                if (error is ChallengeRequiredException) throw error
            }
        }
        throw lastError ?: IllegalStateException(
            "Every download server for '${book.title}' failed."
        )
    }

    // --- fetching -----------------------------------------------------------

    /**
     * Fetches one page. A Cloudflare 503 becomes [ChallengeRequiredException] so
     * the UI can open its WebView; anything else propagates untouched.
     */
    private suspend fun fetchHtml(url: String): String {
        val body = try {
            api.fetch(url)
        } catch (error: retrofit2.HttpException) {
            if (error.code() == 503 || error.code() == 403) throw ChallengeRequiredException(url)
            throw error
        }
        return body.use { response ->
            // Size-capped and charset-detected: a scraped page is
            // attacker-controlled input and is not always UTF-8.
            TextEncoding.readText(response.byteStream(), config.maxPageBytes)
        }
    }

    /**
     * The download URL, checked against the allow-list that
     * [UrlValidator.TRUSTED_DOWNLOAD_HOSTS] declares for this source. The mirror
     * page is remote markup, so a link in it is a claim, not an instruction.
     */
    private fun trustedDownloadUrl(url: String): String =
        UrlValidator.requireTrustedDownloadUrl(url.forceHttps())

    private fun requireValidDownload(file: File, kind: FormatKind, title: String, url: String) {
        val head = runCatching {
            file.inputStream().use { stream ->
                val buffer = ByteArray(2048)
                val read = stream.read(buffer)
                if (read > 0) String(buffer, 0, read, Charsets.ISO_8859_1) else ""
            }
        }.getOrDefault("")
        if (head.contains("Checking your browser", ignoreCase = true) ||
            head.contains("Just a moment", ignoreCase = true)
        ) {
            file.delete()
            throw ChallengeRequiredException(url)
        }
        val valid = when (kind) {
            FormatKind.EPUB -> SafeDownloader.isValidEpub(file)
            FormatKind.PDF -> head.startsWith("%PDF")
            FormatKind.FB2 -> head.contains("<FictionBook", ignoreCase = true)
            else -> head.isNotBlank()
        }
        require(valid) {
            file.delete()
            "The mirror did not return a valid ${kind.name} file for '$title'."
        }
    }

    private fun detailUrlFor(md5: String): String = recordUrl(md5, config)

    private fun safeStem(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(MAX_ID_LENGTH)

    companion object {
        const val ID_PREFIX = "annas"
        private const val TAG = "AnnasArchiveSource"
        private const val DOWNLOAD_DIR = "downloaded_books"
        private const val FIRST_PAGE = 1
        private const val SEARCH_LIMIT = 50
        private const val MAX_ID_LENGTH = 96
        private const val MIN_TITLE_LENGTH = 2
        private const val MAX_TITLE_LENGTH = 200
        private const val MAX_AUTHOR_LENGTH = 160
        private const val MAX_DESCRIPTION_LENGTH = 600
        private const val MAX_DOWNLOAD_BYTES = 60L * 1024 * 1024

        // --- URL ------------------------------------------------------------

        /** The `/md5/<md5>` record page for a book. */
        internal fun recordUrl(md5: String, config: AnnasArchiveConfig = AnnasArchiveConfig()): String =
            "https://${config.host}${config.detailPath.trimEnd('/')}/$md5"

        /** The MIME type a downloaded file of [kind] is stored with. */
        internal fun mimeFor(kind: FormatKind): String = when (kind) {
            FormatKind.EPUB -> "application/epub+zip"
            FormatKind.PDF -> "application/pdf"
            FormatKind.FB2 -> "application/x-fictionbook+xml"
            FormatKind.HTML -> "text/html"
            FormatKind.TXT -> "text/plain"
        }

        /**
         * Builds the search URL:
         * `https://<host>/search?index=&q=…&content=&ext=&sort=`.
         *
         * [HttpUrl] rather than string concatenation: it percent-encodes the
         * query (a Cyrillic or quote-containing title cannot break the request)
         * and rejects a malformed host instead of sending it. Openlib hand-builds
         * this string with `replaceAll(" ", "+")`, which mangles anything else.
         */
        fun buildSearchUrl(
            query: String,
            page: Int = FIRST_PAGE,
            config: AnnasArchiveConfig = AnnasArchiveConfig()
        ): String {
            val builder = HttpUrl.Builder().scheme("https").host(config.host)
            val path = config.searchPath.trim('/')
            if (path.isNotBlank()) builder.addPathSegment(path)
            // The site expects every filter key present, even when empty.
            builder.addQueryParameter("index", "")
            builder.addQueryParameter("q", query)
            builder.addQueryParameter("content", config.contentType)
            builder.addQueryParameter("ext", config.fileType)
            builder.addQueryParameter("sort", config.sort)
            if (page > FIRST_PAGE) builder.addQueryParameter("page", page.toString())
            return builder.build().toString()
        }

        // --- search page (port of Openlib's `_parser`) ----------------------

        /**
         * Parses one search page. Pure: no network, no Android, so it is
         * unit-testable against a saved HTML fixture.
         *
         * Rows that advertise no recognisable format are dropped, matching
         * Openlib's `fileType` filter. If that filter empties a page that
         * clearly had rows, the rows are kept instead of silently returning
         * nothing — a restyled info line must not look like "no results".
         */
        internal fun parseSearchPage(
            html: String,
            config: AnnasArchiveConfig = AnnasArchiveConfig()
        ): List<AnnasArchiveEntry> {
            if (html.isBlank()) return emptyList()

            val doc = Jsoup.parse(html, "https://${config.host}/")
            val entries = selectRows(doc, config).mapNotNull { parseEntry(it, config) }
                .distinctBy { it.md5 }

            if (!config.requireKnownFormat) return entries
            val readable = entries.filter { it.format != null }
            if (readable.isEmpty() && entries.isNotEmpty()) {
                Log.w(TAG, "No row advertised a known format; keeping ${entries.size} row(s) anyway.")
                return entries
            }
            return readable
        }

        /** Configured row selector first, then the structural fallback. */
        private fun selectRows(doc: Document, config: AnnasArchiveConfig): List<Element> {
            for (css in config.selectors.item) {
                val found = select(doc, css)
                if (!found.isNullOrEmpty()) return found
            }
            // Fallback: the repeated ancestor that holds a record link *and* the
            // metadata line is the row, whatever it is called today. Names change;
            // that shape does not.
            val infoSelector = config.selectors.info.first()
            val rows = doc.select("a[href*=/md5/]").take(MAX_ANCHORS).mapNotNull { anchor ->
                var node: Element? = anchor.parent()
                var climbed = 0
                while (node != null && climbed < MAX_CLIMB) {
                    if (runCatching { node.selectFirst(infoSelector) }.getOrNull() != null) return@mapNotNull node
                    node = node.parent()
                    climbed++
                }
                null
            }.distinct()
            if (rows.isNotEmpty()) return rows

            // Last resort: the title anchor's grandparent. Better a row missing
            // its cover than an empty result page.
            return doc.select("a[href*=/md5/]").take(MAX_ANCHORS)
                .mapNotNull { it.parent()?.parent() }
                .distinct()
        }

        private fun parseEntry(row: Element, config: AnnasArchiveConfig): AnnasArchiveEntry? {
            val titleAnchor = parseTitleAnchor(row, config) ?: return null
            val href = titleAnchor.attr("href").takeIf { it.isNotBlank() } ?: return null
            val md5 = md5Of(href) ?: return null
            val title = titleAnchor.text().clean().take(MAX_TITLE_LENGTH)
            if (title.length < MIN_TITLE_LENGTH) return null

            // Openlib walks the title anchor's *siblings* rather than using
            // `:nth-of-type`. Both must point at `/search?q=` to count as an
            // author/publisher rather than some unrelated adjacent link.
            val authorSibling = titleAnchor.nextElementSibling()?.takeIf { it.isSearchLink() }
            val publisherSibling = authorSibling?.nextElementSibling()?.takeIf { it.isSearchLink() }

            val authorRaw = authorSibling?.text()?.clean()
            // Some layouts prefix the author with an icon glyph's text node;
            // Openlib strips the first token when it contains `icon-`.
            val author = authorRaw
                ?.takeIf { it.isNotBlank() }
                ?.let { if (it.contains("icon-")) it.split(' ').drop(1).joinToString(" ").trim() else it }
                ?.takeIf { it.isNotBlank() }
                ?.take(MAX_AUTHOR_LENGTH)

            val publisher = publisherSibling?.text()?.clean()?.takeIf { it.isNotBlank() }

            val info = firstMatch(row, config.selectors.info)?.text()?.clean()?.takeIf { it.isNotBlank() }

            return AnnasArchiveEntry(
                md5 = md5,
                title = title,
                author = author,
                publisher = publisher,
                coverUrl = parseCover(row, config),
                info = info,
                format = formatFrom(info)
            )
        }

        private fun parseTitleAnchor(row: Element, config: AnnasArchiveConfig): Element? {
            for (css in config.selectors.title) {
                val el = firstMatch(row, css)
                if (el != null && el.text().clean().length >= MIN_TITLE_LENGTH) return el
            }
            // The title is the longest record link that is not the cover
            // thumbnail. That holds whatever the class names become.
            return row.select("a[href]")
                .filter { md5Of(it.attr("href")) != null }
                .filter { it.selectFirst("img") == null }
                .maxByOrNull { it.text().clean().length }
                ?.takeIf { it.text().clean().length >= MIN_TITLE_LENGTH }
        }

        private fun parseCover(row: Element, config: AnnasArchiveConfig): String? {
            for (css in config.selectors.cover) {
                firstMatch(row, css)?.let { imageUrl(it) }?.let { return it }
            }
            return row.select("img").mapNotNull(::imageUrl).firstOrNull()
        }

        /** Lazy-loaded covers keep the real URL in a data attribute; `data:` placeholders are dropped. */
        private fun imageUrl(img: Element): String? {
            val raw = LAZY_IMAGE_ATTRIBUTES
                .mapNotNull { img.attr("abs:$it").takeIf { value -> value.isNotBlank() } }
                .firstOrNull()
                ?: img.attr("srcset").takeIf { it.isNotBlank() }
                    ?.substringBefore(',')?.trim()?.substringBefore(' ')?.trim()
            val url = raw?.takeIf { it.isNotBlank() } ?: return null
            if (url.startsWith("data:", ignoreCase = true)) return null
            return url.forceHttps()
        }

        // --- detail page (port of Openlib's `_bookInfoParser`) --------------

        /**
         * Parses a `/md5/<md5>` record page. Returns null when the page is not a
         * record (e.g. the site answered a search page instead).
         */
        internal fun parseBookInfo(
            html: String,
            url: String,
            config: AnnasArchiveConfig = AnnasArchiveConfig()
        ): AnnasArchiveInfo? {
            if (html.isBlank()) return null
            val doc = Jsoup.parse(html, url)
            val main = firstMatch(doc, config.selectors.detailRoot) ?: return null

            val titleElement = firstMatch(main, config.selectors.detailTitle)
            val title = titleElement?.text()?.clean()?.substringBefore("<span")?.trim()
            if (title.isNullOrBlank()) return null

            // Openlib: the author is `a[href^="/search?q="].text-base`, the
            // publisher is that anchor's next sibling, and it only counts when
            // it is itself a `/search?q=` anchor.
            val authorAnchor = firstMatch(main, config.selectors.detailAuthor)
            val publisherAnchor = authorAnchor?.nextElementSibling()
                ?.takeIf { it.tagName().equals("a", ignoreCase = true) && it.isSearchLink() }

            val description = parseDescription(main, config)
            val info = firstMatch(main, config.selectors.info)?.text()?.clean()

            return AnnasArchiveInfo(
                md5 = md5Of(url) ?: "",
                title = title,
                author = authorAnchor?.text()?.clean()?.takeIf { it.isNotBlank() }?.take(MAX_AUTHOR_LENGTH),
                publisher = publisherAnchor?.text()?.clean()?.takeIf { it.isNotBlank() },
                coverUrl = parseCover(main, config),
                info = info,
                description = description,
                format = formatFrom(info),
                mirrorUrl = parseMirrorLink(main, config)
            )
        }

        /**
         * The mirror link. Openlib takes the first `ul.list-inside` anchor whose
         * href contains `/slow_download/`; when the layout changes the same
         * anchor is recognised by its path alone.
         */
        internal fun parseMirrorLink(root: Element, config: AnnasArchiveConfig): String? {
            for (css in config.selectors.mirror) {
                val href = firstMatch(root, css)?.attr("abs:href")
                if (!href.isNullOrBlank()) return href.forceHttps()
            }
            return root.select("a[href]")
                .mapNotNull { it.attr("abs:href").takeIf { value -> value.isNotBlank() } }
                .firstOrNull { it.contains(SLOW_DOWNLOAD_MARKER) }
                ?.forceHttps()
        }

        private fun parseDescription(main: Element, config: AnnasArchiveConfig): String? {
            val label = firstMatch(main, config.selectors.descriptionLabel)
            // The label is checked, not just matched: Openlib verified the text
            // reads "description" so a restyled label cannot hand back the wrong
            // sibling.
            if (label != null && label.text().trim().equals("description", ignoreCase = true)) {
                val text = label.nextElementSibling()?.text()?.clean()
                if (!text.isNullOrBlank()) return text.take(MAX_DESCRIPTION_LENGTH)
            }
            for (css in config.selectors.description) {
                val text = firstMatch(main, css)?.text()?.clean()
                if (!text.isNullOrBlank()) return text.take(MAX_DESCRIPTION_LENGTH)
            }
            return null
        }

        // --- mirror page (port of Openlib's WebView script) -----------------

        /**
         * The candidate file URLs on a mirror page.
         *
         * Openlib resolves these with `evaluateJavascript` inside a WebView:
         * on a `/slow_download/` page it takes
         * `p.mb-4.text-xl.font-bold a`, otherwise every `ul > li > a`. The same
         * two rules are applied here to markup fetched over plain HTTP, which
         * works whenever Cloudflare is not challenging the request.
         */
        internal fun parseMirrorLinks(
            html: String,
            baseUri: String,
            config: AnnasArchiveConfig = AnnasArchiveConfig()
        ): List<String> {
            if (html.isBlank()) return emptyList()
            val doc = Jsoup.parse(html, baseUri)

            val fromSlowPage = doc.select("p.mb-4.text-xl.font-bold a[href]")
                .mapNotNull { it.attr("abs:href").takeIf { value -> value.isNotBlank() } }
            if (fromSlowPage.isNotEmpty()) return fromSlowPage.distinct()

            return doc.select("ul > li > a[href]")
                .mapNotNull { it.attr("abs:href").takeIf { value -> value.isNotBlank() } }
                .distinct()
        }

        // --- format ----------------------------------------------------------

        /**
         * Openlib's `getFormat`: PDF wins, then the comic containers, else EPUB.
         * CBR/CBZ map to null — the built-in reader cannot open them, and
         * advertising a format that fails on tap is worse than not offering it.
         */
        internal fun formatFrom(info: String?): FormatKind? {
            val lower = info?.lowercase(Locale.US) ?: return null
            if (lower.isBlank()) return null
            return when {
                lower.contains("pdf") -> FormatKind.PDF
                lower.contains("cbr") || lower.contains("cbz") -> null
                lower.contains("epub") -> FormatKind.EPUB
                // Openlib defaults to EPUB when nothing matches, because its
                // filter list only offers those four. Here an unrecognised line
                // stays unknown rather than claiming a format.
                else -> null
            }
        }

        // --- helpers ---------------------------------------------------------

        private fun Element.isSearchLink(): Boolean =
            tagName().equals("a", ignoreCase = true) && attr("href").startsWith("/search?q=")

        /** The md5 is the last path segment of a `/md5/…` href or URL. */
        private fun md5Of(url: String): String? {
            val http = url.toHttpUrlOrNull()
            if (http != null) {
                val segments = http.pathSegments.filter { it.isNotBlank() }
                val index = segments.indexOfFirst { it.equals("md5", ignoreCase = true) }
                if (index >= 0) return segments.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
            }
            return url.substringAfterLast('/').takeIf { it.isNotBlank() && url.contains("/md5/") }
        }

        private fun select(root: Element, css: String): List<Element>? =
            runCatching { root.select(css) }.getOrNull()

        private fun firstMatch(root: Element, css: String): Element? =
            select(root, css)?.firstOrNull()

        /** Walks a selector list, first match wins — a typo degrades, never crashes. */
        private fun firstMatch(root: Element, cssList: List<String>): Element? {
            for (css in cssList) {
                firstMatch(root, css)?.let { return it }
            }
            return null
        }

        private fun String.clean(): String =
            replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

        internal fun AnnasArchiveEntry.toBook(): Book = Book(
            id = "${ID_PREFIX}_$md5",
            title = title,
            author = author ?: UNKNOWN_AUTHOR,
            coverUrl = coverUrl,
            description = listOfNotNull(publisher, info).joinToString(" · ").takeIf { it.isNotBlank() },
            fullText = null,
            format = format?.name ?: FormatKind.EPUB.name,
            language = DEFAULT_LANGUAGE,
            subjects = emptyList(),
            isSaved = false
        )

        /**
         * The catalogue card for one row.
         *
         * `isDownloadable`, not `isPublicDomain`: these are copyrighted works
         * that this source can still fetch. `formats` carries the record-page URL
         * rather than a file URL, because the file URL only exists after the
         * mirror page is read — [AnnasArchiveBookSource.downloadContent] walks
         * that chain itself and ignores this placeholder. Standard Ebooks uses
         * the same fallback shape.
         */
        internal fun AnnasArchiveEntry.toCatalogBook(
            config: AnnasArchiveConfig = AnnasArchiveConfig()
        ): CatalogBook = CatalogBook(
            id = "${ID_PREFIX}_$md5",
            title = title,
            authors = author?.let { listOf(Author(it)) } ?: emptyList(),
            coverUrl = coverUrl,
            description = listOfNotNull(publisher, info).joinToString(" · ").takeIf { it.isNotBlank() },
            subjects = emptyList(),
            source = SourceKind.ANNAS_ARCHIVE,
            formats = format?.let { listOf(BookFormat(it, mimeFor(it), recordUrl(md5, config))) }
                ?: emptyList(),
            isPublicDomain = false,
            isDownloadable = true,
            identifiers = BookIdentifiers()
        )

        internal const val UNKNOWN_AUTHOR = "Unknown Author"
        private const val DEFAULT_LANGUAGE = "en"
        private const val SLOW_DOWNLOAD_MARKER = "/slow_download/"
        private const val MAX_ANCHORS = 400
        private const val MAX_CLIMB = 6

        private val LAZY_IMAGE_ATTRIBUTES = listOf(
            "src", "data-src", "data-original", "data-lazy-src", "data-echo", "data-url"
        )
    }
}

/**
 * Everything specific to the Anna's Archive deployment: host, routes and the CSS
 * selectors the site's markup is matched with.
 *
 * The selectors are the ones Openlib ships (`lib/services/annas_archieve.dart`
 * and `lib/ui/webview_page.dart`), kept in the same order so a diff against that
 * app stays readable. Each list has a structural fallback in the parser, so an
 * outdated selector degrades instead of emptying the results.
 */
data class AnnasArchiveConfig(
    val host: String = DEFAULT_HOST,
    val searchPath: String = "/search",
    /** The record page prefix; the md5 is appended. */
    val detailPath: String = "/md5",
    /** Empty by default: no content-type filter, exactly like Openlib's "All". */
    val contentType: String = "",
    /** Empty by default: no extension filter. */
    val fileType: String = "",
    /** Empty by default: "Most Relevant". */
    val sort: String = "",
    val selectors: AnnasArchiveSelectors = AnnasArchiveSelectors(),
    /** Mirrors or CDNs allowed to serve book files, besides [host]. */
    val allowedDownloadHosts: Set<String> = UrlValidator.ANNAS_ARCHIVE_MIRROR_HOSTS,
    /** A scraped page is attacker-controlled; never read it unbounded. */
    val maxPageBytes: Long = DEFAULT_MAX_PAGE_BYTES,
    /**
     * Drop rows whose info line names no format the reader can open. Matches
     * Openlib's `fileType` filter; the parser keeps the rows anyway if the
     * filter would empty a non-empty page.
     */
    val requireKnownFormat: Boolean = true
) {
    companion object {
        const val DEFAULT_HOST = "annas-archive.se"
        const val DEFAULT_MAX_PAGE_BYTES = 4L * 1024 * 1024

        /** Hosts Anna's Archive covers are served from. */
        val COVER_HOSTS: Set<String> = UrlValidator.ANNAS_ARCHIVE_HOSTS
    }
}

/**
 * CSS selectors, tried in order, first match wins.
 *
 * The first entry of each list is the selector Openlib uses verbatim — including
 * `line-clamp-[3]`, whose brackets Jsoup needs escaped. The rest are the same
 * idea expressed so that a class rename does not take the whole parser down.
 */
data class AnnasArchiveSelectors(
    /** One search-result row: `div.flex.pt-3.pb-3.border-b`. */
    val item: List<String> = listOf(
        "div.flex.pt-3.pb-3.border-b",
        "div.border-b.pt-3",
        "div[class*=border-b][class*=pt-3]"
    ),
    /** The title anchor: `a.line-clamp-\[3\].js-vim-focus`. */
    val title: List<String> = listOf(
        """a.line-clamp-\[3\].js-vim-focus""",
        "a.js-vim-focus",
        "a[href*=/md5/]:not(:has(img))"
    ),
    /** The cover thumbnail: `a[href^="/md5/"] img`, and the record page's `div[id^="list_cover_"] img`. */
    val cover: List<String> = listOf(
        """a[href^="/md5/"] img""",
        """a[href*=/md5/] img""",
        """div[id^="list_cover_"] img""",
        "img[src*=cover]"
    ),
    /** The metadata line: `div.text-gray-800`. */
    val info: List<String> = listOf("div.text-gray-800", "div[class*=text-gray-8]"),
    /** The record page body: `div.main-inner`. */
    val detailRoot: List<String> = listOf("div.main-inner", "main", "div#main"),
    /** The record title: `div.font-semibold.text-2xl`. */
    val detailTitle: List<String> = listOf(
        "div.font-semibold.text-2xl",
        "div[class*=font-semibold][class*=text-2xl]",
        "h1"
    ),
    /** The record author: `a[href^="/search?q="].text-base`. */
    val detailAuthor: List<String> = listOf(
        """a[href^="/search?q="].text-base""",
        """a[href^="/search?q="]"""
    ),
    /** The description label inside `div.js-md5-top-box-description`. */
    val descriptionLabel: List<String> = listOf(
        "div.js-md5-top-box-description div.text-xs.text-gray-500.uppercase",
        "div[class*=js-md5-top-box-description] div[class*=uppercase]"
    ),
    val description: List<String> = listOf(
        "div.js-md5-top-box-description div.mb-4",
        "div[class*=js-md5-top-box-description] div[class*=mb-4]"
    ),
    /** The mirror link: `ul.list-inside a[href*="/slow_download/"]`. */
    val mirror: List<String> = listOf(
        """ul.list-inside a[href*="/slow_download/"]""",
        """a[href*="/slow_download/"]"""
    )
)

/** One row of a search page, already resolved to absolute URLs. */
internal data class AnnasArchiveEntry(
    val md5: String,
    val title: String,
    val author: String?,
    val publisher: String?,
    val coverUrl: String?,
    val info: String?,
    val format: FormatKind?
)

/** A parsed `/md5/<md5>` record page. */
internal data class AnnasArchiveInfo(
    val md5: String,
    val title: String,
    val author: String?,
    val publisher: String?,
    val coverUrl: String?,
    val info: String?,
    val description: String?,
    val format: FormatKind?,
    val mirrorUrl: String?
)
