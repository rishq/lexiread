package com.lexiread.data.source

import android.content.Context
import android.util.Log
import android.util.Xml
import com.lexiread.data.remote.api.GutendexApi
import com.lexiread.data.remote.api.OpenLibraryApi
import com.lexiread.data.remote.api.StandardEbooksApi
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

    private val trustedHosts = setOf("gutenberg.org", "gutendex.com", "openlibrary.org", "archive.org")

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
                description = dto.subjects?.joinToString(" • ") ?: "Classic English Literature",
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
 * Open Library metadata catalog. Search only — content is not downloadable
 * here; books are added to the library as saved metadata.
 */
class OpenLibraryBookSource(
    private val openLibraryApi: OpenLibraryApi
) : BookSource {

    override val idPrefix = "ol"
    override val displayName = "Open Library"

    override suspend fun search(query: String): List<Book> {
        val response = openLibraryApi.searchBooks(query)
        return response.docs?.map { doc ->
            val olKey = doc.key.orEmpty().removePrefix("/works/")
            Book(
                id = "${idPrefix}_$olKey",
                title = doc.title,
                author = doc.author_name?.joinToString(", ") ?: "Unknown Author",
                coverUrl = doc.cover_i?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
                description = doc.first_publish_year?.let { "First published in $it." }
                    ?: "Open Library record.",
                fullText = null,
                language = "en",
                subjects = emptyList()
            )
        } ?: emptyList()
    }

    override fun canDownload(book: Book) = false

    override suspend fun downloadContent(book: Book): Book =
        throw UnsupportedOperationException("Open Library does not provide direct book files.")
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
            .firstOrNull { it.id == shortId }
            ?: throw IllegalStateException("Standard Ebooks entry not found for ${book.title}")

        val epubUrl = entry.epubUrl
            ?: throw UnsupportedOperationException("No EPUB available for ${book.title}")

        requireValidUrl(epubUrl)
        val responseBody = seApi.downloadFile(epubUrl)
        responseBody.use { rb ->
            rb.byteStream().use { ins ->
                val destFile = File(booksDir, "se_$shortId.epub")
                FileOutputStream(destFile).use { fos ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    while (true) {
                        val read = ins.read(buffer)
                        if (read == -1) break
                        totalRead += read
                        if (totalRead > MAX_EPUB_BYTES) {
                            destFile.delete()
                            throw IllegalArgumentException("EPUB exceeds ${MAX_EPUB_BYTES} limit.")
                        }
                        fos.write(buffer, 0, read)
                    }
                }
                book.copy(filePath = destFile.absolutePath, format = "EPUB", isSaved = true)
            }
        }
    }

    private fun requireValidUrl(url: String) {
        val uri = java.net.URI(url)
        val ok = uri.scheme?.lowercase() == "https" &&
            uri.host?.lowercase()?.let { it == HOST || it.endsWith(".$HOST") } == true
        if (!ok) throw SecurityException("Download rejected: URL '$url' is not a $HOST link.")
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
                            // OPDS ids look like "https://standardebooks.org/ebooks/author/title"
                            currentId = (currentId + text).substringAfterLast('/')
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
                        "entry" -> flush()
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
