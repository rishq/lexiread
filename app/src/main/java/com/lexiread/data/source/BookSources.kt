package com.lexiread.data.source

import android.content.Context
import android.util.Log
import android.util.Xml
import com.lexiread.data.remote.api.GutendexApi
import com.lexiread.data.remote.api.InternetArchiveApi
import com.lexiread.data.remote.api.StandardEbooksApi
import com.lexiread.data.remote.dto.InternetArchiveFileDto
import com.lexiread.domain.model.Book
import com.lexiread.domain.repository.BookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream

/**
 * Project Gutenberg via the Gutendex API. Provides full plain-text downloads.
 */
class GutendexBookSource(
    private val gutendexApi: GutendexApi
) : BookSource {

    override val idPrefix = "gutenberg"
    override val displayName = "Project Gutenberg"

    private val trustedHosts = setOf("gutenberg.org", "gutendex.com")

    override suspend fun search(query: String): List<Book> {
        val response = gutendexApi.searchBooks(query)
        return response.results?.map { dto ->
            val authorStr = dto.authors?.joinToString(", ") { it.name ?: "" } ?: "Unknown Author"
            val cover = dto.formats?.get("image/jpeg")?.replace("http://", "https://")
                ?: "https://www.gutenberg.org/cache/epub/${dto.id}/pg${dto.id}.cover.medium.jpg"

            Book(
                id = "${idPrefix}_${dto.id}",
                title = dto.title,
                author = authorStr,
                coverUrl = cover,
                description = dto.subjects?.joinToString(" • ")?.takeIf(String::isNotBlank)
                    ?: "Classic English Literature",
                fullText = null,
                language = dto.languages?.firstOrNull() ?: "en",
                subjects = dto.subjects ?: emptyList()
            )
        } ?: emptyList()
    }

    override fun canDownload(book: Book) =
        book.id.removePrefix("${idPrefix}_").toIntOrNull() != null

    override suspend fun downloadContent(book: Book): Book {
        val idNum = book.id.removePrefix("${idPrefix}_").toIntOrNull()
            ?: throw IllegalArgumentException("Not a Gutenberg id: ${book.id}")

        val dto = gutendexApi.getBookById(idNum)
        val textUrl = (dto.formats?.get("text/plain; charset=utf-8")
            ?: dto.formats?.get("text/plain; charset=us-ascii")
            ?: dto.formats?.get("text/plain")
            ?: "https://www.gutenberg.org/files/$idNum/$idNum-0.txt").replace("http://", "https://")

        val responseBody = gutendexApi.downloadTextContent(textUrl)
        val textContent = downloadTextSafely(textUrl, responseBody, trustedHosts)

        return book.copy(fullText = cleanBookText(textContent), isSaved = true)
    }

    companion object {
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

        internal fun downloadTextSafely(
            url: String,
            responseBody: okhttp3.ResponseBody,
            trustedHosts: Set<String>
        ): String {
            // SSRF URL Domain Validation
            val uri = java.net.URI(url)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            val isTrustedDomain = scheme == "https" && host != null &&
                trustedHosts.any { it == host || host.endsWith(".$it") }

            if (!isTrustedDomain) {
                throw SecurityException("Download rejected: URL domain '$host' is not in allowed list.")
            }

            val maxBytes = 10 * 1024 * 1024L
            val contentLength = responseBody.contentLength()
            if (contentLength > maxBytes) {
                throw IllegalArgumentException(
                    "Book content length ($contentLength bytes) exceeds maximum 10MB limit."
                )
            }

            responseBody.byteStream().use { inputStream ->
                val buffer = java.io.ByteArrayOutputStream()
                val data = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L

                while (inputStream.read(data).also { bytesRead = it } != -1) {
                    totalRead += bytesRead
                    if (totalRead > maxBytes) {
                        throw IllegalArgumentException("Downloaded content exceeded 10MB limit.")
                    }
                    buffer.write(data, 0, bytesRead)
                }
                return String(buffer.toByteArray(), Charsets.UTF_8)
            }
        }
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

        val destination = archiveApi.downloadFile(downloadUrl).use { body ->
            writeResponseToFile(
                body = body,
                destination = File(booksDir, "ia_${safeFileStem(identifier)}.${format.lowercase()}"),
                maxBytes = MAX_DOWNLOAD_BYTES
            )
        }
        requireValidEpub(destination)
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

        private fun writeResponseToFile(
            body: okhttp3.ResponseBody,
            destination: File,
            maxBytes: Long
        ): File {
            require(body.contentLength() <= maxBytes || body.contentLength() == -1L) {
                "Book content exceeds the ${maxBytes / (1024 * 1024)}MB limit."
            }

            try {
                body.byteStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var totalBytes = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            totalBytes += read
                            require(totalBytes <= maxBytes) {
                                "Book content exceeds the ${maxBytes / (1024 * 1024)}MB limit."
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                return destination
            } catch (error: Throwable) {
                destination.delete()
                throw error
            }
        }

        private fun requireValidEpub(file: File) {
            val isValid = runCatching {
                java.util.zip.ZipFile(file).use { zip ->
                    zip.getEntry("META-INF/container.xml") != null
                }
            }.getOrDefault(false)
            if (!isValid) {
                file.delete()
                throw IllegalArgumentException("Internet Archive did not return a valid EPUB file.")
            }
        }
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

    companion object {
        private const val TAG = "StandardEbooksSource"
        private const val SEARCH_URL = "https://standardebooks.org/opds/search?query="
        private const val HOST = "standardebooks.org"
        private const val MAX_EPUB_BYTES = 40 * 1024 * 1024L
    }

    private val booksDir = File(context.applicationContext.filesDir, "imported_books").apply { mkdirs() }

    override suspend fun search(query: String): List<Book> {
        val body = seApi.searchOpds("$SEARCH_URL${java.net.URLEncoder.encode(query, "UTF-8")}")
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
        val body = seApi.searchOpds("$SEARCH_URL${java.net.URLEncoder.encode(book.title, "UTF-8")}")
        val entry = body.use { parseOpdsEntries(it.byteStream()) }
            .firstOrNull { it.id == shortId || it.id.substringAfterLast("~") == shortId }
            ?: throw IllegalStateException("Standard Ebooks entry not found for ${book.title}")

        val epubUrl = entry.epubUrl
            ?: throw UnsupportedOperationException("No EPUB available for ${book.title}")

        requireValidUrl(epubUrl)
        val responseBody = seApi.downloadFile(epubUrl)
        responseBody.use { rb ->
            require(rb.contentLength() <= MAX_EPUB_BYTES || rb.contentLength() == -1L) {
                "EPUB exceeds ${MAX_EPUB_BYTES} limit."
            }
            val safeId = shortId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
            val destFile = File(booksDir, "se_$safeId.epub")
            try {
                rb.byteStream().use { ins ->
                    FileOutputStream(destFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var totalRead = 0L
                        while (true) {
                            val read = ins.read(buffer)
                            if (read == -1) break
                            totalRead += read
                            if (totalRead > MAX_EPUB_BYTES) {
                                throw IllegalArgumentException("EPUB exceeds ${MAX_EPUB_BYTES} limit.")
                            }
                            fos.write(buffer, 0, read)
                        }
                    }
                }
                requireValidEpub(destFile)
                book.copy(filePath = destFile.absolutePath, format = "EPUB", isSaved = true)
            } catch (error: Throwable) {
                destFile.delete()
                throw error
            }
        }
    }

    private fun requireValidUrl(url: String) {
        val uri = java.net.URI(url)
        val ok = uri.scheme?.lowercase() == "https" &&
            uri.host?.lowercase()?.let { it == HOST || it.endsWith(".$HOST") } == true
        if (!ok) throw SecurityException("Download rejected: URL '$url' is not a $HOST link.")
    }

    private fun requireValidEpub(file: File) {
        val isValid = runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                zip.getEntry("META-INF/container.xml") != null
            }
        }.getOrDefault(false)
        if (!isValid) {
            file.delete()
            throw IllegalArgumentException("Standard Ebooks did not return a valid EPUB file.")
        }
    }

    private data class OpdsEntry(
        val id: String,
        val title: String,
        val author: String?,
        val summary: String?,
        val coverUrl: String?,
        val epubUrl: String?
    )

    private fun parseOpdsEntries(stream: java.io.InputStream): List<OpdsEntry> {
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
                    when (parser.name.lowercase()) {
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
                    when (parser.name.lowercase()) {
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
