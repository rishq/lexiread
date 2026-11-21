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
 * 1. **Search** — `https://<host>/search?index=&q=…&content=&ext=&sort=`.
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
 * ## Which host
 *
 * The host is not a constant. Anna's Archive has lost `.org`, `.se`, `.li`,
 * `.pm` and `.gs` to seizures, deletions and squatters since 2025, and the
 * replacements land on domains nobody can predict — so the compiled-in host is
 * a *starting point* that is walked over at request time. See
 * [AnnasArchiveConfig.DEFAULT_HOSTS], [AnnasArchiveBookSource.candidateHosts]
 * and the user override in Settings.
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
 * Anna's Archive sits behind DDoS-Guard, which answers a plain request with a
 * JavaScript check that fingerprints the browser. When a fetch comes back 403 —
 * or comes back with the check page itself — the source throws
 * [ChallengeRequiredException], the same signal `MyLibBookSource` uses, and the
 * UI opens [com.lexiread.presentation.common.ChallengeWebViewDialog]. The WebView
 * clears the check, the clearance cookie is copied into the shared cookie jar,
 * and the request is retried with the session attached.
 */
class AnnasArchiveBookSource(
    private val api: AnnasArchiveApi,
    context: Context,
    /**
     * Hosts, routes and selectors. Public so the repository can build the same
     * record-page URLs the parser used; there is one instance of this source and
     * one config behind it.
     */
    val config: AnnasArchiveConfig = AnnasArchiveConfig(),
    /**
     * The user's host override from Settings, read per request rather than
     * captured once, so changing it takes effect without a restart.
     *
     * This exists because the compiled-in host is not dependable: the project
     * has lost `.org`, `.se`, `.li`, `.pm` and `.gs` to seizures and squatters
     * since 2025, and each replacement lands on a domain nobody can predict. An
     * override means the next takedown costs the user a Settings edit instead of
     * an app release.
     */
    private val hostOverride: (suspend () -> String?)? = null
) : BookSource {

    override val idPrefix = ID_PREFIX
    override val displayName = "Anna's Archive"

    private val booksDir = File(context.applicationContext.filesDir, DOWNLOAD_DIR).apply { mkdirs() }

    /**
     * The host that last served a real page, tried before the rest so a normal
     * session pays for the failover walk once at most.
     */
    @Volatile
    private var workingHost: String? = null

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

            withHostFailover(
                build = { host -> buildSearchUrl(normalized, page, config, host) },
                // A real page with no rows is a legitimate "no results", not a
                // bad host, so an empty list is accepted rather than retried.
                parse = { _, html -> parseSearchPage(html, config).take(SEARCH_LIMIT) }
            )
        }

    override fun canDownload(book: Book): Boolean = owns(book)

    override suspend fun downloadContent(book: Book): Book = withContext(Dispatchers.IO) {
        val md5 = book.id.removePrefix("${ID_PREFIX}_")
        require(md5.isNotBlank()) { "Not an Anna's Archive id: ${book.id}" }

        // 1. The detail page carries the mirror link. It is derived from the md5
        //    rather than remembered from search, so a book restored from the
        //    library still downloads after a process restart. *Which host* serves
        //    it is discovered here, because the compiled-in one is regularly
        //    seized — see `candidateHosts`.
        val (_, info) = withHostFailover(
            build = { host -> recordUrl(md5, config, host) },
            parse = { url, html -> parseBookInfo(html, url, config)?.let { url to it } }
        )

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
     * Fetches one page. A browser check becomes [ChallengeRequiredException] so
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
     * The hosts to try, best first: the user's override, then whichever host
     * last answered, then the compiled-in order.
     *
     * This is the whole answer to "the source is unavailable". The domains are
     * not stable — `.org` and `.se` stopped resolving, `.li` was deleted and is
     * now a domain-sale page — so the working host is found by asking, not by
     * being compiled in.
     */
    private suspend fun candidateHosts(): List<String> {
        val override = sanitizeHost(runCatching { hostOverride?.invoke() }.getOrNull())
        return (listOfNotNull(override, workingHost) + config.hosts).distinct()
    }

    /**
     * Runs [build] against each candidate host until [parse] yields a value.
     *
     * `parse` returning `null` means "this host did not give me what I asked
     * for" — a lapsed domain, a squatted parking page, a restyled error — and
     * the next host is tried.
     *
     * A [ChallengeRequiredException] is remembered rather than re-raised
     * immediately: another host may serve the page without a challenge, and
     * walking the list only to hand the user a WebView is a worse outcome. If
     * *every* host challenges, the challenge is rethrown so the UI can still
     * offer the WebView.
     */
    private suspend fun <T> withHostFailover(
        build: (host: String) -> String,
        parse: (url: String, html: String) -> T?
    ): T {
        var lastError: Throwable? = null
        var challenge: ChallengeRequiredException? = null

        for (host in candidateHosts()) {
            val url = try {
                build(host)
            } catch (error: Throwable) {
                // A malformed host (the override is user input) must skip the
                // host, not abort the walk.
                lastError = error
                continue
            }
            val html = try {
                fetchHtml(url)
            } catch (error: ChallengeRequiredException) {
                challenge = error
                continue
            } catch (error: Throwable) {
                lastError = error
                continue
            }
            // Some configurations answer the check with 200 instead of 403. The
            // page itself is the reliable signal, so it is checked before the
            // archive markers: a challenge page is not a host that went stale.
            if (looksLikeChallenge(html)) {
                challenge = ChallengeRequiredException(url)
                continue
            }
            if (!looksLikeArchive(html)) {
                lastError = IllegalStateException(
                    "Anna's Archive host '$host' answered, but not with the archive."
                )
                continue
            }
            val parsed = parse(url, html)
            if (parsed == null) {
                lastError = IllegalStateException("Anna's Archive host '$host' returned no usable page.")
                continue
            }
            workingHost = host
            // Covers are fetched by Coil through a separate allow-list, which has
            // to know about a host we only discovered at runtime.
            AnnasArchiveConfig.rememberDiscoveredHost(host)
            return parsed
        }
        throw challenge ?: lastError
        ?: IllegalStateException("No Anna's Archive host is reachable.")
    }

    /**
     * Reduces user input to a bare hostname, or `null` if it is not one.
     *
     * The override is the user's own instruction, so it is not required to be in
     * the compiled-in allow-list — that list is exactly what rots. It is still
     * reduced to a hostname: a value like `evil.example/x`, `host:8080` or
     * `javascript:` must never reach the URL builder, and `HttpUrl.Builder`
     * throwing on a bad host would otherwise surface as an opaque crash.
     */
    internal fun sanitizeHost(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase(Locale.US) ?: return null
        val withoutScheme = trimmed.removePrefix("https://").removePrefix("http://")
        val host = withoutScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .trimEnd('.')
        if (host.isBlank() || host.length > MAX_HOST_LENGTH) return null
        return host.takeIf { HOST_PATTERN.matches(it) }
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

        /** The `/md5/<md5>` record page for a book, on [host]. */
        internal fun recordUrl(
            md5: String,
            config: AnnasArchiveConfig = AnnasArchiveConfig(),
            host: String = config.host
        ): String = "https://$host${config.detailPath.trimEnd('/')}/$md5"

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
            config: AnnasArchiveConfig = AnnasArchiveConfig(),
            host: String = config.host
        ): String {
            val builder = HttpUrl.Builder().scheme("https").host(host)
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
        private const val MAX_HOST_LENGTH = 253
        /** How much of a page is scanned for markers. The markers are early in the markup. */
        private const val MARKER_SCAN_CHARS = 200_000

        /** A bare DNS hostname — labels of alphanumerics and hyphens, no port and no path. */
        private val HOST_PATTERN = Regex(
            "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$"
        )

        /**
         * Text only a domain-squatting or ad page carries.
         *
         * These are the services actually seen serving Anna's Archive's lapsed
         * domains on 2026-10-10: AboveDomains on `.li`, ParkLogic on `.pm`, and
         * an ad-network hop on `.gs`.
         */
        private val PARKING_MARKERS = listOf(
            "abovedomains",
            "parklogic",
            "clickpathworks",
            "sedoparking",
            "buy this domain",
            "domain is for sale",
            "domain for sale",
            "forsale",
            "domain parking"
        )

        /**
         * Whether a fetched page is a browser check rather than content.
         *
         * Anna's Archive sits behind DDoS-Guard, which answers a plain request
         * with a JavaScript check that fingerprints the browser. The check is
         * usually a 403, but it can also be served with 200 — the page, not
         * the status code, is the reliable signal.
         *
         * Only a WebView can pass it, so the page is reported as a challenge
         * and the UI is asked to open a browser on it.
         */
        internal fun looksLikeChallenge(html: String): Boolean {
            if (html.isBlank()) return false
            val sample = html.take(MARKER_SCAN_CHARS).lowercase(Locale.US)
            return CHALLENGE_MARKERS.any { sample.contains(it) }
        }

        /**
         * Text the real site always carries, so a blank or gutted page is not taken for it.
         */
        private val ARCHIVE_MARKERS = listOf("anna's archive", "annas-archive", "annasarchive")

        /** Markers of the browser checks the site answers plain requests with. */
        private val CHALLENGE_MARKERS = listOf(
            "ddos-guard",
            "ddg-l10n-title",
            "checking your browser",
            "just a moment",
            "cf-browser-verification",
            "challenge-platform"
        )

        /**
         * Whether a fetched page really is Anna's Archive.
         *
         * A 200 response proves nothing here. `.li` answers 200 with an
         * AboveDomains sale page, `.pm` with a ParkLogic parking page and `.gs`
         * with an ad-network redirect; each of those would otherwise be parsed as
         * a search result, or — worse — accepted as a working host and asked for
         * a book file.
         */
        internal fun looksLikeArchive(html: String): Boolean {
            if (html.isBlank()) return false
            val sample = html.take(MARKER_SCAN_CHARS).lowercase(Locale.US)
            if (PARKING_MARKERS.any { sample.contains(it) }) return false
            return ARCHIVE_MARKERS.any { sample.contains(it) }
        }

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
    /**
     * Candidate hosts, best first — a *failover order*, not a constant.
     *
     * The project's domains are seized and re-registered every few months, so
     * the source walks this list until one answers with a real page. [host]
     * below is the preferred entry.
     */
    val hosts: List<String> = DEFAULT_HOSTS,
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
    /** The preferred host — used to build URLs until failover moves on. */
    val host: String get() = hosts.firstOrNull() ?: DEFAULT_HOST

    companion object {
        /**
         * The current preferred host. Verified by DNS-over-HTTPS on 2026-10-10:
         * `.org`, `.se` and `.in` no longer resolve at all, and `.li`, `.pm` and
         * `.gs` have been taken over by domain-sale, parking and ad services.
         *
         * See `UrlValidator.ANNAS_ARCHIVE_HOSTS` for the full history.
         * **Re-verify before changing this, and never add a lapsed domain back**
         * — a squatted host in the trusted set is worse than a missing one.
         */
        const val DEFAULT_HOST = "annas-archive.gl"

        /** Failover order. All three resolved on 2026-10-10. */
        val DEFAULT_HOSTS: List<String> = listOf(
            DEFAULT_HOST,
            "annas-archive.pk",
            "annas-archive.gd"
        )

        const val DEFAULT_MAX_PAGE_BYTES = 4L * 1024 * 1024

        /**
         * Hosts discovered at request time: the user's override, or whichever
         * host answered when the compiled-in ones were down.
         *
         * Covers are loaded by Coil through `CoverUrls.sanitize`, which is handed
         * the allowed hosts by `catalogCoverHostsFor`. That is a pure mapping
         * from a book id and cannot read Settings, so this registry is how a host
         * discovered at request time becomes trusted for the cover of the book
         * that came from it. Only the source writes it, and only with a host it
         * actually fetched from.
         */
        @Volatile
        private var discoveredHosts: Set<String> = emptySet()

        internal fun rememberDiscoveredHost(host: String) {
            discoveredHosts = discoveredHosts + host
        }

        /** Hosts Anna's Archive covers may be served from. */
        val COVER_HOSTS: Set<String> get() = UrlValidator.ANNAS_ARCHIVE_HOSTS + discoveredHosts
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
