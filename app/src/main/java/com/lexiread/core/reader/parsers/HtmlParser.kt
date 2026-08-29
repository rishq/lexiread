package com.lexiread.core.reader.parsers

import com.lexiread.core.reader.BookParser
import com.lexiread.core.reader.ChapterParser
import com.lexiread.core.reader.ParsedBookMetadata
import com.lexiread.core.util.TextEncoding
import com.lexiread.domain.model.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads a standalone HTML book — the middle rung of the
 * EPUB -> HTML -> plain text preference chain.
 *
 * Gutenberg publishes many titles only as HTML, and without this parser a
 * search result for such a book would resolve to "no readable edition found"
 * even though the text is right there.
 */
class HtmlParser : BookParser {

    override fun canParse(format: String, file: File): Boolean {
        val normalized = format.lowercase()
        return normalized == "html" ||
            normalized == "htm" ||
            file.extension.lowercase() == "html" ||
            file.extension.lowercase() == "htm"
    }

    override suspend fun parseChapters(file: File): List<BookChapter> = withContext(Dispatchers.IO) {
        val raw = runCatching { readDocument(file) }.getOrNull() ?: return@withContext emptyList()
        val body = extractBody(raw)

        splitIntoSections(body)
            .mapNotNull { (title, html) ->
                val content = ChapterParser.cleanHtmlText(html)
                if (content.isBlank()) null else title to content
            }
            .mapIndexed { index, (title, content) ->
                BookChapter(title = title, content = content, index = index)
            }
    }

    override suspend fun extractMetadata(file: File): ParsedBookMetadata = withContext(Dispatchers.IO) {
        val raw = runCatching { readDocument(file) }.getOrNull()

        ParsedBookMetadata(
            title = raw?.let { TITLE_REGEX.find(it)?.groupValues?.getOrNull(1) }
                ?.let { ChapterParser.cleanHtmlText(it) }
                ?.takeIf { it.isNotBlank() }
                ?: file.nameWithoutExtension.replace("_", " "),
            author = raw?.let { metaContent(it, "author") }?.takeIf { it.isNotBlank() }
                ?: "Unknown Author",
            description = raw?.let { metaContent(it, "description") }?.takeIf { it.isNotBlank() }
                ?: "Imported HTML book."
        )
    }

    private fun readDocument(file: File): String =
        file.inputStream().use { TextEncoding.readText(it, MAX_FILE_SIZE_BYTES) }

    /** Drops `head`, `script` and `style`; falls back to the whole document. */
    internal fun extractBody(raw: String): String {
        val withoutHead = raw
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<head[\\s\\S]*?</head>", RegexOption.IGNORE_CASE), " ")

        val bodyMatch = BODY_REGEX.find(withoutHead)
        return bodyMatch?.groupValues?.getOrNull(1) ?: withoutHead
    }

    /**
     * Splits on h1-h3 headings. Content before the first heading is dropped: in
     * a Gutenberg HTML file that region is the title page and licence notice,
     * which would otherwise become chapter 1.
     */
    internal fun splitIntoSections(html: String): List<Pair<String, String>> {
        val matches = HEADING_REGEX.findAll(html).toList()
        if (matches.isEmpty()) return listOf("Chapter 1" to html)

        return matches.mapIndexed { index, match ->
            val end = if (index < matches.lastIndex) matches[index + 1].range.first else html.length
            val title = ChapterParser.cleanHtmlText(match.groupValues[2])
                .take(TITLE_MAX_LENGTH)
                .ifBlank { "Chapter ${index + 1}" }
            title to html.substring(match.range.first, end)
        }
    }

    private fun metaContent(html: String, name: String): String? {
        val escaped = Regex.escape(name)
        val patterns = listOf(
            Regex("<meta[^>]*name=[\"']$escaped[\"'][^>]*content=[\"']([^\"']*)[\"'][^>]*>", RegexOption.IGNORE_CASE),
            Regex("<meta[^>]*content=[\"']([^\"']*)[\"'][^>]*name=[\"']$escaped[\"'][^>]*>", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }?.trim()
    }

    private companion object {
        const val MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024
        const val TITLE_MAX_LENGTH = 80

        val BODY_REGEX = Regex("<body[^>]*>([\\s\\S]*)</body>", RegexOption.IGNORE_CASE)
        val HEADING_REGEX = Regex("<h([1-3])[^>]*>([\\s\\S]*?)</h\\1>", RegexOption.IGNORE_CASE)
        val TITLE_REGEX = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
    }
}
