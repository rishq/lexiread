package com.lexiread.data.source

import android.content.Context
import android.util.Log
import com.lexiread.core.util.UrlValidator
import com.lexiread.data.remote.api.PgaApi
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.FormatKind
import com.lexiread.domain.repository.BookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File

/**
 * Project Gutenberg Australia — a frozen, all-English, public-domain collection
 * (~6,000 ebooks) served as a single plain-text index plus per-book `.txt` /
 * `.html` files. There is no JSON/OPDS API, so this source parses the index
 * itself and derives each book's download URL from its 7-digit catalogue number.
 *
 * URLs in the index are `http://gutenberg.net.au/ebooksYY/NNNNNNNh.html`
 * (and, for older books, a sibling `.txt`). The number's first two digits are
 * the upload year; we upgrade everything to HTTPS before downloading.
 */
class PgaBookSource(
    private val pgaApi: PgaApi,
    context: Context
) : BookSource {

    override val idPrefix = ID_PREFIX
    override val displayName = "Project Gutenberg Australia"

    private val booksDir = File(context.applicationContext.filesDir, DOWNLOAD_DIR).apply { mkdirs() }

    /** Parsed index, kept for the session so repeated searches hit the network once. */
    private var cachedEntries: List<PgaEntry>? = null

    override suspend fun search(query: String): List<Book> = withContext(Dispatchers.IO) {
        val entries = entries()
        val q = query.trim().lowercase()
        val filtered = if (q.isBlank()) {
            entries
        } else {
            entries.filter { it.title.lowercase().contains(q) || it.author.lowercase().contains(q) }
        }
        filtered.take(SEARCH_LIMIT).map { it.toBook() }
    }

    override fun canDownload(book: Book): Boolean = owns(book)

    override suspend fun downloadContent(book: Book): Book = withContext(Dispatchers.IO) {
        val number = book.id.removePrefix("${idPrefix}_").removePrefix(idPrefix).trim()
        require(number.matches(NUMBER_PATTERN)) { "Not a Project Gutenberg Australia id: ${book.id}" }
        val year = number.take(2)
        val txtUrl = "https://gutenberg.net.au/ebooks$year/$number.txt"
        val htmlUrl = "https://gutenberg.net.au/ebooks$year/${number}h.html"

        val raw = runCatching {
            // Prefer the clean plain-text edition; fall back to the HTML page
            // (recent additions only ship HTML) if no `.txt` exists.
            pgaApi.fetch(UrlValidator.requireTrustedDownloadUrl(txtUrl)).use { it.string() }
        }.getOrElse {
            pgaApi.fetch(UrlValidator.requireTrustedDownloadUrl(htmlUrl)).use { it.string() }
                .let(::stripHtml)
        }

        val cleaned = cleanPgaText(raw)
        val destination = File(booksDir, "$idPrefix${number}.txt")
        destination.writeText(cleaned, Charsets.UTF_8)

        book.copy(filePath = destination.absolutePath, format = FormatKind.TXT.name, isSaved = true)
    }

    private suspend fun entries(): List<PgaEntry> {
        cachedEntries?.let { return it }
        val text = pgaApi.fetch(INDEX_URL).string()
        return parseIndex(text).also { cachedEntries = it }
    }

    companion object {
        const val ID_PREFIX = "pga"
        private const val TAG = "PgaSource"
        private const val DOWNLOAD_DIR = "downloaded_books"
        private const val INDEX_URL = "https://gutenberg.net.au/gutindex_aus.txt"
        private const val SEARCH_LIMIT = 50

        private val NUMBER_PATTERN = Regex("\\d{7}")
        private val URL_PATTERN =
            Regex("""https?://gutenberg\.net\.au/ebooks(\d{2})/(\d{7})(h)?\.(html|txt)""")
        private val AUTHOR_OVERRIDE_PATTERN = Regex("""\[Author:\s*(.+?)\s*]""", RegexOption.IGNORE_CASE)
        private val DATE_PREFIX_PATTERN = Regex("""^\s*[A-Z][a-z]{2}\s+\d{4}\s+""")

        /**
         * Parses the `gutindex_aus.txt` catalogue into structured entries.
         *
         * Each entry is a title line (optionally followed by an `[Author: …]`
         * override) and one or two URL lines. Blank lines and the free-text
         * header are harmlessly skipped: an entry is only emitted when a title
         * line is followed by at least one URL line.
         */
        internal fun parseIndex(text: String): List<PgaEntry> {
            val entries = mutableListOf<PgaEntry>()
            var titleLine: String? = null
            var authorOverride: String? = null
            val urls = mutableListOf<String>()

            fun flush() {
                if (titleLine != null && urls.isNotEmpty()) {
                    val urlMatch = URL_PATTERN.find(urls.first()) ?: return
                    val number = urlMatch.groupValues[2]
                    val (title, author) = parseTitleAuthor(titleLine!!, authorOverride)
                    if (title.isNotBlank()) {
                        entries.add(
                            PgaEntry(
                                number = number,
                                title = title,
                                author = author,
                                txtUrl = urls.firstOrNull { it.endsWith(".txt", ignoreCase = true) }?.toHttps(),
                                htmlUrl = urls.firstOrNull { it.endsWith(".html", ignoreCase = true) }?.toHttps()
                            )
                        )
                    }
                }
                titleLine = null
                authorOverride = null
                urls.clear()
            }

            for (raw in text.lineSequence()) {
                val line = raw.trimEnd()
                if (line.isBlank()) continue

                val urlMatch = URL_PATTERN.find(line)
                if (urlMatch != null) {
                    if (titleLine != null) urls += line.trim()
                    continue
                }

                val authorMatch = AUTHOR_OVERRIDE_PATTERN.find(line)
                if (authorMatch != null) {
                    authorOverride = authorMatch.groupValues[1].trim()
                    continue
                }

                // Any other non-empty line starts a new entry.
                flush()
                titleLine = line
            }
            flush()
            return entries
        }

        private fun parseTitleAuthor(line: String, override: String?): Pair<String, String> {
            val noDate = DATE_PREFIX_PATTERN.replace(line, "").trim()
            val bracketIdx = noDate.indexOf(" [")
            val left = if (bracketIdx >= 0) noDate.substring(0, bracketIdx) else noDate
            val comma = left.lastIndexOf(',')
            return if (comma >= 0) {
                val title = left.substring(0, comma).trim()
                val author = override ?: left.substring(comma + 1).trim().removePrefix("by ").trim()
                (title.ifBlank { left.trim() }) to (author.ifBlank { UNKNOWN_AUTHOR })
            } else {
                left.trim() to (override ?: UNKNOWN_AUTHOR)
            }
        }

        internal fun PgaEntry.toBook(): Book = Book(
            id = "${ID_PREFIX}_$number",
            title = title,
            author = author,
            coverUrl = null,
            description = null,
            fullText = null,
            format = FormatKind.TXT.name,
            language = "en",
            subjects = emptyList(),
            isSaved = false
        )

        private fun String.toHttps(): String =
            if (startsWith("https", ignoreCase = true)) this else replace("http://", "https://")

        /**
         * Strips HTML to readable prose: drops script/style/head blocks, removes
         * tags, decodes the common entities and collapses whitespace.
         */
        internal fun stripHtml(html: String): String {
            val withoutBlocks = html.replace(
                Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>", RegexOption.IGNORE_CASE),
                " "
            )
            return withoutBlocks
                .replace(Regex("(?is)<[^>]*>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        /**
         * Project Gutenberg Australia text files open with a short licence
         * notice. If the very first line names the project, drop everything up
         * to the first blank line so the reader opens on the actual text.
         */
        internal fun cleanPgaText(raw: String): String {
            val lines = raw.split("\n")
            val start = if (lines.firstOrNull()?.contains("Project Gutenberg", ignoreCase = true) == true) {
                (lines.indexOfFirst { it.isBlank() } + 1).coerceAtLeast(0)
            } else {
                0
            }
            return lines.drop(start).joinToString("\n").trim()
        }

        internal const val UNKNOWN_AUTHOR = "Unknown Author"
    }

    internal data class PgaEntry(
        val number: String,
        val title: String,
        val author: String,
        val txtUrl: String?,
        val htmlUrl: String?
    )
}
