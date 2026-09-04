package com.lexiread.data.source

import android.content.Context
import com.lexiread.core.util.BookFormatSelector
import com.lexiread.core.util.SafeDownloader
import com.lexiread.core.util.TextEncoding
import com.lexiread.core.util.UrlValidator
import com.lexiread.core.util.forceHttps
import com.lexiread.data.remote.api.MyLibApi
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.BookFormat
import com.lexiread.domain.model.FormatKind
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
 * Scrapes a Mylib-style catalogue: an HTML site with no API at all, reachable
 * only through its search route.
 *
 * Two rules shape this source, and both are deliberate:
 *
 * 1. **Jsoup parses, it does not fetch.** `Jsoup.connect()` would open a second,
 *    unmanaged HTTP stack — no shared cache, no shared timeouts, no logging, and
 *    no way to fake it in a test. The page is fetched through the app's Retrofit
 *    client and handed to [Jsoup.parse] as a string, so the network behaves
 *    exactly like it does for every other catalogue.
 *
 * 2. **A failure is a failure.** The original sketch swallowed every exception
 *    and returned `emptyList()`, which made "the site is down" and "no such
 *    book" indistinguishable and hid 503s forever. Here exceptions propagate:
 *    `BooksRepositoryImpl` retries them, isolates them per source and surfaces
 *    the source name in `CatalogPage.failedSources`.
 *
 * The markup is unknown until the site is live, so [MyLibConfig.selectors] holds
 * the CSS selectors and the parser degrades gracefully: every lookup walks a
 * list of candidate selectors, and if none of them match it falls back to
 * detecting the repeated row structure instead of returning nothing.
 */
class MyLibBookSource(
    private val api: MyLibApi,
    context: Context,
    private val config: MyLibConfig = MyLibConfig()
) : BookSource {

    override val idPrefix = ID_PREFIX
    override val displayName = "My Library"

    private val booksDir = File(context.applicationContext.filesDir, DOWNLOAD_DIR).apply { mkdirs() }

    /**
     * Entries seen on the search pages parsed so far, keyed by id.
     *
     * The search page is the only place that exposes direct file links, and an
     * id alone is not enough to rebuild them on this site, so the download step
     * needs what the search step saw. Bounded and evicted oldest-first, so a
     * long browsing session cannot grow without limit.
     */
    private val remembered = object : LinkedHashMap<String, MyLibEntry>(
        MAX_REMEMBERED_ENTRIES, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MyLibEntry>?): Boolean =
            size > MAX_REMEMBERED_ENTRIES
    }

    override suspend fun search(query: String): List<Book> = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        // An empty `q` asks the site for its entire catalogue; not a search.
        if (normalized.isBlank()) return@withContext emptyList()

        val url = buildSearchUrl(normalized, FIRST_PAGE, config)
        val page = fetchPage(url, FIRST_PAGE)
        remember(page.entries)
        page.entries.take(SEARCH_LIMIT).map { it.toBook() }
    }

    override fun canDownload(book: Book): Boolean = owns(book)

    override suspend fun downloadContent(book: Book): Book = withContext(Dispatchers.IO) {
        val id = book.id.removePrefix("${ID_PREFIX}_")
        val entry = lookup(id)
            ?: throw IllegalStateException(
                "The download links for '${book.title}' are no longer in memory. " +
                        "Search for the book again and open it from the results."
            )

        val format = BookFormatSelector.pickBest(entry.formats)
            ?: throw UnsupportedOperationException(
                "No edition of '${book.title}' is available in a format the reader can open."
            )

        // The host is configuration, not code, so it is passed in rather than
        // added to the global allow-list. It is still checked, never trusted.
        val url = UrlValidator.requireTrustedDownloadUrl(format.url.forceHttps(), config.downloadHosts())
        val destination = File(booksDir, "${ID_PREFIX}_${safeStem(entry.id)}.${extensionFor(format.kind)}")

        api.fetch(url).use { body ->
            SafeDownloader.downloadToFile(body, destination, MAX_DOWNLOAD_BYTES)
        }
        if (format.kind == FormatKind.EPUB) {
            require(SafeDownloader.isValidEpub(destination)) {
                destination.delete()
                "The catalogue did not return a valid EPUB file for '${book.title}'."
            }
        }

        book.copy(filePath = destination.absolutePath, format = format.kind.name, isSaved = true)
    }

    /** Parses one search page. Exposed so the repository can page without a second fetch. */
    internal suspend fun fetchPage(url: String, page: Int): MyLibSearchPage {
        val html = api.fetch(url).use { body ->
            // Size-capped and charset-detected: a scraped page is attacker-controlled
            // input, and plenty of catalogues are still served as windows-1251.
            TextEncoding.readText(body.byteStream(), config.maxPageBytes)
        }
        return parseSearchPage(html, url, page, config)
    }

    @Synchronized
    internal fun remember(entries: List<MyLibEntry>) {
        entries.forEach { remembered[it.id] = it }
    }

    @Synchronized
    private fun lookup(id: String): MyLibEntry? = remembered[id]

    companion object {
        const val ID_PREFIX = "mylib"
        private const val DOWNLOAD_DIR = "downloaded_books"
        private const val FIRST_PAGE = 1
        private const val SEARCH_LIMIT = 50
        private const val MAX_REMEMBERED_ENTRIES = 200
        private const val MAX_DOWNLOAD_BYTES = 40L * 1024 * 1024

        // --- URL ------------------------------------------------------------

        /**
         * Builds the search URL: `https://<host>/s/?q=…&languages[]=…&extensions[]=…`
         *
         * [HttpUrl] rather than string concatenation: it percent-encodes the query
         * (so a Cyrillic or quote-containing title cannot break the request) and
         * it rejects a malformed host instead of sending it.
         */
        fun buildSearchUrl(query: String, page: Int = FIRST_PAGE, config: MyLibConfig = MyLibConfig()): String {
            val builder = HttpUrl.Builder().scheme("https").host(config.host)
            val trimmed = config.searchPath.trim('/')
            if (trimmed.isNotBlank()) {
                // Trailing slash: the site's search route is `/s/`, not `/s`.
                builder.addPathSegments("$trimmed/")
            }
            if (query.isNotBlank()) builder.addQueryParameter(config.queryParameter, query)
            config.languages.forEach { builder.addQueryParameter(config.languageParameter, it) }
            config.extensions.forEach { builder.addQueryParameter(config.extensionParameter, it) }
            config.contentTypes.forEach { builder.addQueryParameter(config.contentTypeParameter, it) }
            if (page > FIRST_PAGE) builder.addQueryParameter(config.pageParameter, page.toString())
            return builder.build().toString()
        }

        // --- parsing --------------------------------------------------------

        /**
         * Parses one search page. Pure: no network, no Android, unit-testable
         * against a saved HTML fixture.
         */
        internal fun parseSearchPage(
            html: String,
            baseUri: String,
            requestedPage: Int = FIRST_PAGE,
            config: MyLibConfig = MyLibConfig()
        ): MyLibSearchPage {
            if (html.isBlank()) return MyLibSearchPage(emptyList(), null, false)

            val doc = Jsoup.parse(html, baseUri)
            val entries = selectItems(doc, config)
                .mapNotNull { parseEntry(it, config) }
                .distinctBy { it.id }

            val total = parseTotalResults(doc, config)
            val explicitNext = parseHasNextPage(doc, requestedPage, config)
            val hasNext = when {
                explicitNext != null -> explicitNext
                total != null -> requestedPage * entries.size.coerceAtLeast(1) < total
                // No pagination markup at all: a full page is the best signal we
                // have, and asking for one page too many costs one request.
                else -> entries.size >= ASSUME_MORE_THRESHOLD
            }
            return MyLibSearchPage(entries, total, hasNext)
        }

        /**
         * Row detection. Configured selectors first; when the markup has changed
         * and none of them match, look for the repeated structure instead of
         * silently returning nothing.
         */
        private fun selectItems(doc: Document, config: MyLibConfig): List<Element> {
            for (css in config.selectors.item) {
                val found = select(doc, css) ?: continue
                if (found.isNotEmpty()) return found
            }
            return autoDetectItems(doc, config)
        }

        /**
         * The ancestor whose children each hold exactly one book or file link is
         * the result row, whatever it is called today.
         *
         * Names change far more often than structure does, so this survives the
         * usual "we restyled the catalogue" event.
         */
        private fun autoDetectItems(doc: Document, config: MyLibConfig): List<Element> {
            val anchors = doc.select("a[href]")
                .filter { isBookAnchor(it.absUrl("href"), config) }
                .take(MAX_ANCHORS)
            if (anchors.isEmpty()) return emptyList()

            var best: List<Element> = emptyList()
            for (anchor in anchors) {
                var node: Element? = anchor.parent()
                var climbed = 0
                while (node != null && climbed < MAX_CLIMB) {
                    val rows = node.children().filter { child ->
                        child.select("a[href]").any { isBookAnchor(it.absUrl("href"), config) }
                    }
                    if (rows.size >= best.size) best = rows
                    node = node.parent()
                    climbed++
                }
            }
            return best
        }

        private fun parseEntry(item: Element, config: MyLibConfig): MyLibEntry? {
            val titleAnchor = parseTitleAnchor(item, config)
            val title = titleAnchor?.text()?.clean()
                ?.takeIf { it.length >= MIN_TITLE_LENGTH }
                ?: item.text().clean().takeIf { it.length >= MIN_TITLE_LENGTH }
                ?: return null

            val meta = parseMeta(item)
            val detailUrl = parseDetailUrl(item, config, titleAnchor)
            val id = deriveId(detailUrl, title, meta) ?: return null

            return MyLibEntry(
                id = id,
                title = title.take(MAX_TITLE_LENGTH),
                author = parseAuthor(item, config, meta),
                coverUrl = parseCover(item, config),
                detailUrl = detailUrl,
                formats = parseFormats(item),
                language = meta["language"] ?: meta["lang"] ?: meta["язык"],
                year = parseYear(meta),
                description = parseDescription(item, config, meta)
            )
        }

        private fun parseTitleAnchor(item: Element, config: MyLibConfig): Element? {
            for (css in config.selectors.title) {
                val el = firstMatch(item, css)
                if (el != null && el.text().clean().length >= MIN_TITLE_LENGTH) return el
            }
            // Titles are links, and in every layout seen so far the longest link
            // text in a row is the title.
            return item.select("a[href]")
                .filterNot { isFileUrl(it.attr("abs:href")) }
                .maxByOrNull { it.text().clean().length }
                ?.takeIf { it.text().clean().length >= MIN_TITLE_LENGTH }
                ?: firstMatch(item, "h1,h2,h3,h4")
        }

        private fun parseAuthor(item: Element, config: MyLibConfig, meta: Map<String, String>): String? {
            for (css in config.selectors.author) {
                firstMatch(item, css)?.text()?.clean()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return cleanAuthor(it) }
            }
            // Naming is the most stable thing about scraped markup: an element
            // called "author" is an author, whatever tag wraps it.
            val fromClass = item.allElements.firstOrNull { el ->
                el !== item && AUTHOR_HINT.matches("${el.className()} ${el.id()}")
            }?.text()?.clean()
            if (!fromClass.isNullOrBlank()) return cleanAuthor(fromClass)
            return meta["author"]?.let(::cleanAuthor)
                ?: meta["authors"]?.let(::cleanAuthor)
                ?: meta["автор"]?.let(::cleanAuthor)
        }

        private fun parseCover(item: Element, config: MyLibConfig): String? {
            for (css in config.selectors.cover) {
                firstMatch(item, css)?.let { imageUrl(it) }?.let { return it }
            }
            return item.select("img").mapNotNull(::imageUrl).firstOrNull()
        }

        /**
         * Lazy-loaded covers keep the real URL in a data attribute, and some
         * themes ship an inline `data:` placeholder that must not be handed to
         * the image loader.
         */
        private fun imageUrl(img: Element): String? {
            val raw = LAZY_IMAGE_ATTRIBUTES
                .mapNotNull { img.attr("abs:$it").takeIf { value -> value.isNotBlank() } }
                .firstOrNull()
                ?: img.attr("srcset")
                    .takeIf { it.isNotBlank() }
                    ?.substringBefore(',')
                    ?.trim()
                    ?.substringBefore(' ')
                    ?.trim()

            val url = raw?.takeIf { it.isNotBlank() } ?: return null
            if (url.startsWith("data:", ignoreCase = true)) return null
            val absolute = if (url.startsWith("http", ignoreCase = true)) {
                url
            } else {
                runCatching { java.net.URI(img.baseUri()).resolve(url).toString() }.getOrNull()
            }
            return absolute?.forceHttps()
        }

        private fun parseDetailUrl(item: Element, config: MyLibConfig, titleAnchor: Element?): String? {
            for (css in config.selectors.detail) {
                firstMatch(item, css)?.attr("abs:href")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            titleAnchor?.attr("abs:href")?.takeIf { it.isNotBlank() }?.let { return it }
            return item.select("a[href]")
                .map { it.attr("abs:href") }
                .firstOrNull { it.isNotBlank() && !isFileUrl(it) }
        }

        /**
         * Direct file links in the row, one per kind, best first.
         *
         * Links are recognised by extension in the URL *or* by the link text
         * ("EPUB", "FB2"), because both conventions are common. Only EPUB, HTML
         * and TXT are returned: those are the ones `BookFormatSelector.pickBest`
         * can open, and advertising a format the reader cannot open produces a
         * card that looks readable and then fails on tap.
         */
        private fun parseFormats(item: Element): List<BookFormat> {
            val found = LinkedHashMap<FormatKind, BookFormat>()
            for (anchor in item.select("a[href]")) {
                val href = anchor.attr("abs:href")
                if (href.isBlank()) continue
                val fromText = anchor.text().trim().uppercase(Locale.US)
                    .takeIf { it in KNOWN_FILE_EXTENSIONS }
                    ?.lowercase(Locale.US)
                val extension = fileExtensionOf(href) ?: fromText
                val (kind, mime) = extension?.let { READABLE_EXTENSIONS[it] } ?: continue
                if (found.containsKey(kind)) continue
                found[kind] = BookFormat(kind = kind, mimeType = mime, url = href.forceHttps())
            }
            return found.values.toList()
        }

        /**
         * `Key: Value` pairs, from `<dt>/<dd>`, `<th>/<td>` and plain
         * "Publisher: …" spans. This is where year, language, publisher and file
         * size live on every catalogue layout seen so far.
         */
        private fun parseMeta(item: Element): Map<String, String> {
            val meta = LinkedHashMap<String, String>()

            fun pairs(labelTag: String, valueTag: String) {
                for (label in item.select(labelTag)) {
                    val key = label.text().clean().removeSuffix(":").lowercase(Locale.US)
                    val value = label.nextElementSibling()
                        ?.takeIf { it.tagName() == valueTag }
                        ?.text()?.clean()
                    if (key.isNotBlank() && !value.isNullOrBlank()) meta.putIfAbsent(key, value)
                }
            }
            pairs("dt", "dd")
            pairs("th", "td")

            // "Year: 1998" style elements. ownText() keeps a parent from
            // swallowing its children's labels.
            for (el in item.allElements) {
                val match = KEY_VALUE.matchEntire(el.ownText().clean()) ?: continue
                val key = match.groupValues[1].trim().lowercase(Locale.US)
                val value = match.groupValues[2].trim()
                if (value.isNotBlank()) meta.putIfAbsent(key, value)
            }
            return meta
        }

        private fun parseYear(meta: Map<String, String>): Int? {
            val candidates = listOfNotNull(
                meta["year"], meta["published"], meta["date"], meta["publication year"], meta["год"]
            )
            return candidates.firstNotNullOfOrNull { candidate ->
                YEAR.find(candidate)?.value?.toIntOrNull()?.takeIf { it in MIN_YEAR..MAX_YEAR }
            }
        }

        private fun parseDescription(
            item: Element,
            config: MyLibConfig,
            meta: Map<String, String>
        ): String? {
            for (css in config.selectors.description) {
                firstMatch(item, css)?.text()?.clean()
                    ?.takeIf { it.length >= MIN_DESCRIPTION_LENGTH }
                    ?.let { return it.take(MAX_DESCRIPTION_LENGTH) }
            }
            val parts = listOfNotNull(
                meta["publisher"]?.let { "Publisher: $it" },
                meta["series"]?.let { "Series: $it" },
                meta["pages"]?.let { "$it pages" },
                meta["size"]?.let { "Size: $it" }
            )
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

        /**
         * A stable id for the book, so a card can be downloaded, deduplicated and
         * cached. Taken from the detail URL (query parameter, then path segment),
         * then from the row metadata, and only then from the title.
         */
        private fun deriveId(detailUrl: String?, title: String, meta: Map<String, String>): String? {
            val http = detailUrl?.toHttpUrlOrNull()
            if (http != null) {
                for (parameter in ID_QUERY_PARAMETERS) {
                    http.queryParameter(parameter)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { return slug(it) }
                }
                val segments = http.pathSegments.filter { it.isNotBlank() }
                for (marker in DETAIL_MARKERS) {
                    val index = segments.indexOf(marker)
                    if (index >= 0) segments.getOrNull(index + 1)?.let { return slug(it) }
                }
                segments.lastOrNull()?.takeIf { it.length > 1 }?.let { return slug(it) }
            }
            meta["md5"]?.takeIf { it.isNotBlank() }?.let { return slug(it) }
            return slug(title).takeIf { it.isNotBlank() }
        }

        // --- pagination ------------------------------------------------------

        private fun paginationBlocks(doc: Document, config: MyLibConfig): List<Element> {
            val blocks = mutableListOf<Element>()
            for (css in config.selectors.pagination) {
                select(doc, css)?.let { blocks.addAll(it) }
            }
            doc.allElements
                .filter { PAGINATION_HINT.matches("${it.className()} ${it.id()}") }
                .forEach { blocks.add(it) }
            return blocks
        }

        private fun parseTotalResults(doc: Document, config: MyLibConfig): Int? {
            val scope = paginationBlocks(doc, config).joinToString(" ") { it.text() }
            if (scope.isBlank()) return null
            return TOTAL_PATTERNS.firstNotNullOfOrNull { pattern ->
                pattern.find(scope)
                    ?.groupValues?.getOrNull(1)
                    ?.replace(THOUSAND_SEPARATORS, "")
                    ?.toIntOrNull()
            }
        }

        /** Null when the page carries no pagination at all — "unknown", not "no". */
        private fun parseHasNextPage(doc: Document, requestedPage: Int, config: MyLibConfig): Boolean? {
            val relNext = select(doc, "a[rel=next]")?.firstOrNull()
            if (relNext != null) return true

            val blocks = paginationBlocks(doc, config)
            if (blocks.isEmpty()) return null

            for (anchor in blocks.flatMap { it.select("a[href]") }) {
                val label = anchor.text().clean().lowercase(Locale.US)
                if (label in NEXT_LABELS) return true
                val target = anchor.absUrl("href")
                    .toHttpUrlOrNull()
                    ?.queryParameter(config.pageParameter)
                    ?.toIntOrNull()
                if (target != null && target > requestedPage) return true
            }
            return false
        }

        // --- link classification ---------------------------------------------

        private fun fileExtensionOf(url: String): String? {
            val http = url.toHttpUrlOrNull() ?: return null
            return http.pathSegments
                .lastOrNull { it.isNotBlank() }
                ?.substringAfterLast('.', "")
                ?.takeIf { it.isNotBlank() }
                ?.lowercase(Locale.US)
        }

        private fun isFileUrl(url: String): Boolean = fileExtensionOf(url) in KNOWN_FILE_EXTENSIONS

        /**
         * Whether a link points at a book rather than at navigation.
         *
         * Pagination links share the search route and carry the same filters we
         * just sent, so they have to be excluded explicitly — otherwise every
         * "page 2" link looks like a book.
         */
        private fun isBookAnchor(url: String, config: MyLibConfig): Boolean {
            if (url.isBlank()) return false
            val http = url.toHttpUrlOrNull() ?: return false
            val path = http.encodedPath
            val searchPath = "/${config.searchPath.trim('/')}"
            if (path == searchPath || path == "$searchPath/") return false
            if (config.queryParameter in http.queryParameterNames) return false
            return isFileUrl(url) || DETAIL_MARKERS.any { path.contains(it) }
        }

        // --- helpers ----------------------------------------------------------

        /** A typo in a selector must degrade to the next candidate, not crash a search. */
        private fun select(root: Element, css: String): List<Element>? =
            runCatching { root.select(css) }.getOrNull()

        private fun firstMatch(root: Element, css: String): Element? =
            select(root, css)?.firstOrNull()

        private fun String.clean(): String =
            replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

        private fun cleanAuthor(value: String): String = value
            .removePrefix("by ")
            .removePrefix("By ")
            .trim()
            .trimEnd(',')
            .take(MAX_AUTHOR_LENGTH)
            .takeIf { it.isNotBlank() }
            ?: UNKNOWN_AUTHOR

        private fun slug(value: String): String = value
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .take(MAX_ID_LENGTH)

        private fun safeStem(id: String): String = slug(id).takeIf { it.isNotBlank() } ?: "book"

        private fun extensionFor(kind: FormatKind): String = when (kind) {
            FormatKind.EPUB -> "epub"
            FormatKind.HTML -> "html"
            else -> "txt"
        }

        internal fun MyLibEntry.toBook(): Book = Book(
            id = "${ID_PREFIX}_$id",
            title = title,
            author = author ?: UNKNOWN_AUTHOR,
            coverUrl = coverUrl,
            description = description,
            fullText = null,
            format = BookFormatSelector.pickBest(formats)?.kind?.name ?: FormatKind.TXT.name,
            language = language ?: DEFAULT_LANGUAGE,
            subjects = emptyList(),
            isSaved = false
        )

        internal const val UNKNOWN_AUTHOR = "Unknown Author"
        private const val DEFAULT_LANGUAGE = "en"

        private const val MIN_TITLE_LENGTH = 2
        private const val MAX_TITLE_LENGTH = 200
        private const val MAX_AUTHOR_LENGTH = 160
        private const val MAX_ID_LENGTH = 96
        private const val MIN_DESCRIPTION_LENGTH = 16
        private const val MAX_DESCRIPTION_LENGTH = 600
        private const val MIN_YEAR = 1000
        private const val MAX_YEAR = 2100
        private const val MAX_ANCHORS = 600
        private const val MAX_CLIMB = 6
        private const val ASSUME_MORE_THRESHOLD = 25

        /**
         * Every format the reader can open, with the MIME type the download and
         * import steps key off. Extending this is the only change needed to make
         * PDF or FB2 readable — but a format advertised here must also be one
         * `BookFormatSelector.pickBest` is willing to choose.
         */
        private val READABLE_EXTENSIONS: Map<String, Pair<FormatKind, String>> = mapOf(
            "epub" to (FormatKind.EPUB to "application/epub+zip"),
            "html" to (FormatKind.HTML to "text/html"),
            "htm" to (FormatKind.HTML to "text/html"),
            "txt" to (FormatKind.TXT to "text/plain")
        )

        /** Recognised but not necessarily readable — used to spot file links. */
        private val KNOWN_FILE_EXTENSIONS: Set<String> = READABLE_EXTENSIONS.keys + setOf(
            "pdf", "fb2", "mobi", "azw", "azw3", "djvu", "doc", "docx", "rtf", "zip", "rar"
        )

        private val DETAIL_MARKERS = listOf(
            "book", "md5", "details", "item", "record", "title", "view", "download"
        )
        private val ID_QUERY_PARAMETERS = listOf("id", "md5", "book", "book_id", "item")

        private val LAZY_IMAGE_ATTRIBUTES = listOf(
            "src", "data-src", "data-original", "data-lazy-src", "data-echo", "data-url"
        )

        private val AUTHOR_HINT =
            Regex("(?i).*\\b(authors?|aut(?:hor)?s?|avtor|writer)\\b.*")
        private val PAGINATION_HINT =
            Regex("(?i).*(pagin|pager|page-nav|count|total|found|result|найдено|страниц).*")
        private val KEY_VALUE =
            Regex("""([A-Za-zА-Яа-яЁё][A-Za-zА-Яа-яЁё /_-]{1,24})\s*:\s*(.+)""")
        private val YEAR = Regex("""\b(1\d{3}|20\d{2})\b""")

        /** Thousands separators, including the non-breaking ones catalogues use. */
        private val THOUSAND_SEPARATORS = Regex("[\\s\\u00A0,._']")

        private val TOTAL_PATTERNS = listOf(
            Regex("""\bof\s+([\d\s,.\u00A0]{1,16})""", RegexOption.IGNORE_CASE),
            Regex(
                """([\d\s,.\u00A0]{1,16})\s*(?:results?|books?|titles?|records?|найдено|записей|книг)""",
                RegexOption.IGNORE_CASE
            ),
            Regex("""(?:found|total)\D{0,20}([\d\s,.\u00A0]{1,16})""", RegexOption.IGNORE_CASE)
        )

        private val NEXT_LABELS = setOf(
            "next", "next page", "next ›", "next »", "›", "»", ">",
            "далее", "следующая", "вперёд"
        )
    }
}

/**
 * Everything specific to one Mylib deployment.
 *
 * Host, route, filter parameter names and CSS selectors live here rather than
 * being spread through the parser, so pointing the source at a mirror — or
 * repairing it after the site restyles — is a one-line change.
 */
data class MyLibConfig(
    val host: String = DEFAULT_HOST,
    val searchPath: String = "/s/",
    val languages: List<String> = listOf("english"),
    val extensions: List<String> = listOf("EPUB", "TXT", "FB2", "PDF"),
    val contentTypes: List<String> = listOf("book"),
    val queryParameter: String = "q",
    val pageParameter: String = "page",
    val languageParameter: String = "languages[]",
    val extensionParameter: String = "extensions[]",
    val contentTypeParameter: String = "selected_content_types[]",
    val selectors: MyLibSelectors = MyLibSelectors(),
    /** Mirrors or CDNs allowed to serve book files, besides [host]. */
    val allowedDownloadHosts: Set<String> = emptySet(),
    /** A scraped page is attacker-controlled; never read it unbounded. */
    val maxPageBytes: Long = DEFAULT_MAX_PAGE_BYTES
) {
    /** Hosts files may be fetched from: this deployment plus its mirrors. */
    fun downloadHosts(): Set<String> = allowedDownloadHosts + host

    companion object {
        const val DEFAULT_HOST = "zlib.bz"
        const val DEFAULT_MAX_PAGE_BYTES = 4L * 1024 * 1024
    }
}

/**
 * CSS selectors, tried in order, first match wins. Each list falls back to a
 * structural heuristic, so an outdated selector slows the parse down rather than
 * emptying the results.
 */
data class MyLibSelectors(
    /** One result row. */
    val item: List<String> = listOf(
        "div.book-item", "li.book-item", "tr.book-row", "[data-book-id]",
        "div.record", "div.result", "li.result", "div.res-item"
    ),
    /** The title link. */
    val title: List<String> = listOf(
        "h3.title a", ".title a", "a.title", "h3 a", "h2 a", "td.title a"
    ),
    val author: List<String> = listOf(
        ".author", ".authors", "span.author", "div.author", "td.author"
    ),
    val cover: List<String> = listOf(
        "img.cover", ".cover img", "img[src*=\"cover\"]", "img[src*=\"Cover\"]"
    ),
    /** Link to the book's own page. */
    val detail: List<String> = listOf(
        "a[href*=\"/book/\"]", "a[href*=\"/md5/\"]", "a[href*=\"/details/\"]",
        ".title a", "h3 a"
    ),
    val description: List<String> = listOf(
        ".description", ".annotation", ".summary", ".snippet"
    ),
    val pagination: List<String> = listOf(
        ".pagination", "nav.pagination", "div.pager", "ul.pager", ".pages"
    )
)

/** One row of a search page, already resolved to absolute URLs. */
internal data class MyLibEntry(
    val id: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val detailUrl: String?,
    val formats: List<BookFormat>,
    val language: String?,
    val year: Int?,
    val description: String?
)

internal data class MyLibSearchPage(
    val entries: List<MyLibEntry>,
    val totalResults: Int?,
    val hasNextPage: Boolean
)
