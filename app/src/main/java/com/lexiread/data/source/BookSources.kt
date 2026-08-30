package com.lexiread.data.source

import android.content.Context
import android.util.Log
import android.util.Xml
import com.lexiread.core.util.BookFormatSelector
import com.lexiread.core.util.SafeDownloader
import com.lexiread.core.util.TextEncoding
import com.lexiread.core.util.UrlValidator
import com.lexiread.data.mapper.toCatalogBook
import com.lexiread.data.mapper.toDomainBook
import com.lexiread.data.remote.api.InternetArchiveApi
import com.lexiread.data.remote.api.StandardEbooksApi
import com.lexiread.data.remote.dto.InternetArchiveFileDto
import com.lexiread.data.remote.gutendex.GutendexApi
import com.lexiread.data.remote.openlibrary.OpenLibraryApi
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.FormatKind
import com.lexiread.domain.repository.BookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Project Gutenberg via the Gutendex API. Primary reading source: everything it
 * returns is public domain, so full text may be downloaded legally.
 */
class GutendexBookSource(
    private val gutendexApi: GutendexApi,
    context: Context
) : BookSource {

    override val idPrefix = "gutenberg"
    override val displayName = "Project Gutenberg"

    private val booksDir = File(context.applicationContext.filesDir, DOWNLOAD_DIR).apply { mkdirs() }

    override suspend fun search(query: String): List<Book> {
        val response = gutendexApi.searchBooks(query)
        return response.results.orEmpty().map { dto -> dto.toCatalogBook().toDomainBook() }
    }

    override fun canDownload(book: Book): Boolean = gutenbergId(book) != null

    /**
     * Picks the best format purely from the MIME types Gutendex advertised, in
     * the order EPUB, HTML, plain text. No URL is ever assembled from a
     * template: if Gutenberg renames or drops a file the next format is used
     * instead of a 404.
     */
    override suspend fun downloadContent(book: Book): Book = withContext(Dispatchers.IO) {
        val idNum = gutenbergId(book)
            ?: throw IllegalArgumentException("Not a Gutenberg id: ${book.id}")

        val dto = gutendexApi.getBookById(idNum)
        val format = BookFormatSelector.pickBest(BookFormatSelector.fromGutendexFormats(dto.formats))
            ?: throw UnsupportedOperationException(
                "No readable EPUB, HTML or text edition is published for '${book.title}'."
            )

        val url = UrlValidator.requireTrustedDownloadUrl(format.url)
        val destination = File(booksDir, "gutenberg_$idNum.${extensionFor(format.kind)}")
        gutendexApi.downloadFile(url).use { body ->
            SafeDownloader.downloadToFile(body, destination, MAX_DOWNLOAD_BYTES)
        }

        when (format.kind) {
            FormatKind.EPUB -> require(SafeDownloader.isValidEpub(destination)) {
                destination.delete()
                "Gutenberg did not return a valid EPUB file for '${book.title}'."
            }
            FormatKind.TXT -> stripGutenbergBoilerplate(destination)
            else -> Unit
        }

        book.copy(
            filePath = destination.absolutePath,
            format = format.kind.name,
            isSaved = true
        )
    }

    private fun gutenbergId(book: Book): Int? =
        book.id.removePrefix("${idPrefix}_").toIntOrNull()

    companion object {
        private const val DOWNLOAD_DIR = "downloaded_books"
        private const val MAX_DOWNLOAD_BYTES = 25L * 1024 * 1024

        internal fun extensionFor(kind: FormatKind): String = when (kind) {
            FormatKind.EPUB -> "epub"
            FormatKind.HTML -> "html"
            else -> "txt"
        }

        internal fun cleanBookText(rawText: String): String {
            var text = rawText
            val headerIdx = text.indexOf("*** START OF THE PROJECT GUTENBERG EBOOK")
            if (headerIdx != -1) {
                val endHeaderIdx = text.indexOf("***", headerIdx + 40)
                if (endHeaderIdx != -1) {
                    text = text.substring(endHeaderIdx + 3)
                }
            }
            val footerIdx = text.indexOf("*** END OF THE PROJECT GUTENBERG EBOOK")
            if (footerIdx != -1) {
                text = text.substring(0, footerIdx)
            }
            return text.trim()
        }

        /**
         * Rewrites a freshly downloaded text file without its licence header and
         * footer, normalised to UTF-8. Doing it once at download time means the
         * reader never has to re-scan multi-megabyte text on every open.
         */
        private fun stripGutenbergBoilerplate(file: File) {
            val decoded = runCatching {
                file.inputStream().use { TextEncoding.readText(it, MAX_DOWNLOAD_BYTES) }
            }.getOrNull() ?: return
            val cleaned = cleanBookText(decoded)
            if (cleaned.length != decoded.length) {
                runCatching { file.writeText(cleaned, Charsets.UTF_8) }
            }
        }
    }
}

/**
 * Open Library. Catalogue, metadata and covers — never a download source.
 *
 * Open Library exposes no endpoint that streams public-domain full text the way
 * Gutendex does, so this source contributes discoverability and cover art while
 * reading is delegated to whichever source does have a readable edition.
 */
class OpenLibraryBookSource(
    private val openLibraryApi: OpenLibraryApi
) : BookSource {

    override val idPrefix = "ol"
    override val displayName = "Open Library"

    override suspend fun search(query: String): List<Book> {
        if (query.isBlank()) return emptyList()
        val response = openLibraryApi.searchBooks(query)
        return response.docs.orEmpty().mapNotNull { doc -> doc.toCatalogBook()?.toDomainBook() }
    }

    override fun canDownload(book: Book): Boolean = false

    override suspend fun downloadContent(book: Book): Book {
        throw UnsupportedOperationException(
            "Open Library provides catalogue data only. Read '${book.title}' from another source."
        )
    }
}

/**
 * Internet Archive texts that advertise a downloadable EPUB. Metadata is
 * checked to reject borrow-only records and to obtain the exact file name.
 */
class InternetArchiveBookSource(
    private val archiveApi: InternetArchiveApi,
    context: Context
) : BookSource {

    override val idPrefix = "ia"
    override val displayName = "Internet Archive"

    private val booksDir = File(context.applicationContext.filesDir, "downloaded_books").apply { mkdirs() }

    override suspend fun search(query: String): List<Book> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()

        val response = archiveApi.searchBooks(
            query = buildSearchQuery(normalizedQuery),
            rows = SEARCH_RESULT_LIMIT
        )
        return response.response?.docs.orEmpty().mapNotNull { doc ->
            val identifier = doc.identifier?.takeIf(::isSafeIdentifier) ?: return@mapNotNull null
            val title = doc.title?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val year = doc.year.toDisplayText()

            Book(
                id = "${idPrefix}_$identifier",
                title = title,
                author = doc.creator.toDisplayText() ?: "Unknown Author",
                coverUrl = "https://archive.org/services/img/$identifier",
                description = listOfNotNull("Internet Archive EPUB edition", year?.let { "published $it" })
                    .joinToString("; ") + ".",
                fullText = null,
                format = "EPUB",
                subjects = listOf("Internet Archive", "Downloadable EPUB")
            )
        }
    }

    override fun canDownload(book: Book): Boolean = owns(book) && isSafeIdentifier(identifierFrom(book))

    override suspend fun downloadContent(book: Book): Book = withContext(Dispatchers.IO) {
        val identifier = identifierFrom(book)
        require(isSafeIdentifier(identifier)) { "Invalid Internet Archive identifier." }

        val item = archiveApi.getMetadata(identifier)
        require(item.metadata?.accessRestricted?.equals("true", ignoreCase = true) != true) {
            "This Internet Archive item is available only through borrowing."
        }
        val file = item.files.orEmpty()
            .let(::selectReadableFile)
            ?: throw UnsupportedOperationException("No public EPUB or text file is available for '${book.title}'.")
        val fileName = file.name.orEmpty()
        val format = formatFor(fileName, file.format)
        val downloadUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(ARCHIVE_HOST)
            .addPathSegment("download")
            .addPathSegment(identifier)
            .addPathSegment(fileName)
            .build()
            .toString()

        val destination = File(booksDir, "ia_${safeFileStem(identifier)}.${format.lowercase()}")
        archiveApi.downloadFile(downloadUrl).use { body ->
            SafeDownloader.downloadToFile(body, destination, MAX_DOWNLOAD_BYTES)
        }
        require(SafeDownloader.isValidEpub(destination)) {
            destination.delete()
            "Internet Archive did not return a valid EPUB file."
        }
        book.copy(filePath = destination.absolutePath, format = format, isSaved = true)
    }

    private fun identifierFrom(book: Book): String = book.id.removePrefix("${idPrefix}_")

    private fun buildSearchQuery(query: String): String {
        val escaped = query.replace("\\", "\\\\").replace("\"", "\\\"")
        return "mediatype:texts AND format:EPUB AND (title:\"$escaped\" OR creator:\"$escaped\")"
    }

    private fun isSafeIdentifier(identifier: String): Boolean =
        identifier.matches(IDENTIFIER_PATTERN)

    private fun Any?.toDisplayText(): String? = when (this) {
        is String -> trim().takeIf(String::isNotBlank)
        is List<*> -> mapNotNull { it as? String }.joinToString(", ").takeIf(String::isNotBlank)
        is Number -> toString()
        else -> null
    }

    private fun safeFileStem(identifier: String): String =
        identifier.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)

    companion object {
        private const val ARCHIVE_HOST = "archive.org"
        private const val SEARCH_RESULT_LIMIT = 20
        private const val MAX_DOWNLOAD_BYTES = 40L * 1024 * 1024
        private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        internal fun selectReadableFile(files: List<InternetArchiveFileDto>): InternetArchiveFileDto? =
            files.asSequence()
                .filter { it.private?.equals("true", ignoreCase = true) != true }
                .filter { file ->
                    val name = file.name.orEmpty()
                    name.isNotBlank() && '/' !in name && '\\' !in name
                }
                .firstOrNull { fileRank(it.name.orEmpty(), it.format) == 0 }

        private fun fileRank(name: String, format: String?): Int {
            val normalizedFormat = format.orEmpty().lowercase()
            val normalizedName = name.lowercase()
            return when {
                normalizedFormat == "epub" || normalizedName.endsWith(".epub") -> 0
                else -> Int.MAX_VALUE
            }
        }

        private fun formatFor(name: String, format: String?): String =
            if (format.orEmpty().equals("epub", ignoreCase = true) || name.endsWith(".epub", ignoreCase = true)) "EPUB" else "TXT"
    }
}

/**
 * Standard Ebooks (public domain, professionally formatted EPUBs) via OPDS.
 */
class StandardEbooksBookSource(
    private val seApi: StandardEbooksApi,
    context: Context
) : BookSource {

    override val idPrefix = "se"
    override val displayName = "Standard Ebooks"

    private val booksDir = File(context.applicationContext.filesDir, "imported_books").apply { mkdirs() }

    override suspend fun search(query: String): List<Book> {
        val body = seApi.searchOpds("${SEARCH_URL}${java.net.URLEncoder.encode(query, "UTF-8")}&page=1&per-page=20")
        val entries = body.use { parseOpdsEntries(it.byteStream()) }
        return entries.map { e ->
            Book(
                id = "${idPrefix}_${e.id}",
                title = e.title,
                author = e.author ?: "Unknown Author",
                coverUrl = e.coverUrl,
                description = e.summary ?: "A Standard Ebooks edition.",
                fullText = null,
                format = "EPUB"
            )
        }
    }

    override fun canDownload(book: Book) = owns(book)

    override suspend fun downloadContent(book: Book): Book = withContext(Dispatchers.IO) {
        // Re-resolve the acquisition link from the feed entry for this id.
        val shortId = book.id.removePrefix("${idPrefix}_")
        // Standard Ebooks OPDS also offers a per-book page; simplest robust path:
        // search by title again and match the id.
        val body = seApi.searchOpds("$SEARCH_URL${java.net.URLEncoder.encode(book.title, "UTF-8")}&page=1&per-page=20")
        val entry = body.use { parseOpdsEntries(it.byteStream()) }
            .firstOrNull { it.id == shortId || it.id.substringAfterLast("~") == shortId }
            ?: throw IllegalStateException("Standard Ebooks entry not found for ${book.title}")

        val epubUrl = entry.epubUrl
            ?: throw UnsupportedOperationException("No EPUB available for ${book.title}")

        UrlValidator.requireTrustedDownloadUrl(epubUrl)
        val safeId = shortId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        val destFile = File(booksDir, "se_$safeId.epub")
        seApi.downloadFile(epubUrl).use { rb ->
            SafeDownloader.downloadToFile(rb, destFile, MAX_EPUB_BYTES)
        }
        require(SafeDownloader.isValidEpub(destFile)) {
            destFile.delete()
            "Standard Ebooks did not return a valid EPUB file."
        }
        book.copy(filePath = destFile.absolutePath, format = "EPUB", isSaved = true)
    }

    companion object {
        private const val TAG = "StandardEbooksSource"
        private const val SEARCH_URL = "https://standardebooks.org/feeds/atom/all?query="
        private const val HOST = "standardebooks.org"
        private const val MAX_EPUB_BYTES = 40 * 1024 * 1024L

        data class OpdsEntry(
            val id: String,
            val title: String,
            val author: String?,
            val summary: String?,
            val coverUrl: String?,
            val epubUrl: String?
        )

        fun parseOpdsEntries(stream: java.io.InputStream): List<OpdsEntry> {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)

            val entries = mutableListOf<OpdsEntry>()
            var currentId = ""
            var currentTitle = ""
            var currentAuthor: String? = null
            var currentSummary: String? = null
            var currentCover: String? = null
            var currentEpub: String? = null
            var inEntry = false
            var tag: String? = null
            var authorDone = false

            fun flush() {
                if (inEntry && currentTitle.isNotBlank()) {
                    entries.add(OpdsEntry(currentId, currentTitle, currentAuthor, currentSummary, currentCover, currentEpub))
                }
            }

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name?.lowercase()) {
                            "entry" -> {
                                flush()
                                inEntry = true
                                currentId = ""; currentTitle = ""; currentAuthor = null
                                currentSummary = null; currentCover = null; currentEpub = null
                                authorDone = false
                            }
                            "id" -> if (inEntry) { tag = "id"; currentId = "" }
                            "title" -> if (inEntry) { tag = "title"; currentTitle = "" }
                            "name" -> if (inEntry && !authorDone) tag = "name"
                            "summary" -> if (inEntry) { tag = "summary"; currentSummary = "" }
                            "thumbnail" -> if (inEntry) {
                                val url = parser.getAttributeValue(null, "url")
                                if (url != null && currentCover == null) {
                                    currentCover = absolutize(url)
                                }
                            }
                            "link" -> if (inEntry) {
                                val rel = parser.getAttributeValue(null, "rel") ?: ""
                                val href = parser.getAttributeValue(null, "href")
                                val type = parser.getAttributeValue(null, "type") ?: ""
                                if (href != null) {
                                    when {
                                        rel.contains("/image") && currentCover == null ->
                                            currentCover = absolutize(href)
                                        type.contains("epub+zip") && currentEpub == null ->
                                            currentEpub = absolutize(href)
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        when (tag) {
                            "id" -> {
                                // OPDS ids look like "https://standardebooks.org/ebooks/author/title".
                                // Retain the author because titles can repeat across authors.
                                currentId = (currentId + text)
                                    .substringAfter("/ebooks/")
                                    .trimEnd('/')
                                    .replace("/", "~")
                            }
                            "title" -> currentTitle += text
                            "name" -> if (currentAuthor.isNullOrBlank()) {
                                currentAuthor = text
                                authorDone = true
                            }
                            "summary" -> currentSummary = (currentSummary ?: "") + text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name?.lowercase()) {
                            "entry" -> {
                                flush()
                                inEntry = false
                            }
                            "id", "title", "name", "summary" -> tag = null
                        }
                    }
                }
                event = parser.next()
            }
            flush()

            Log.d(TAG, "Parsed ${entries.size} OPDS entries")
            return entries
        }

        private fun absolutize(href: String): String =
            if (href.startsWith("http")) href else "https://$HOST$href"
    }
}
