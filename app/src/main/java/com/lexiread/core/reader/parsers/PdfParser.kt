package com.lexiread.core.reader.parsers

import android.util.Log
import com.lexiread.core.reader.BookParser
import com.lexiread.core.reader.ChapterParser
import com.lexiread.core.reader.ParsedBookMetadata
import com.lexiread.domain.model.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

class PdfParser : BookParser {

    companion object {
        private const val TAG = "PdfParser"
        // P1-1: 80 MB + ~2x UTF-16 String copy + StringBuilder reliably OOMs on
        // 128-256 MB heap devices. 25 MB keeps peak (file bytes + decoded text
        // capped below) within budget; larger files get a friendly placeholder.
        private const val MAX_PDF_BYTES = 25 * 1024 * 1024L // 25 MB cap
        /** Cap on a single inflated stream. The file cap cannot cover this: a
         * few hundred KB of compressed data can inflate to gigabytes. */
        private const val MAX_INFLATED_BYTES = 32 * 1024 * 1024L // 32 MB
        /** P1-1: cap on total extracted text so the StringBuilder itself cannot OOM. */
        private const val MAX_EXTRACTED_TEXT_CHARS = 5 * 1024 * 1024 // ~10 MB as UTF-16
        private const val TJ_OPERATOR = "Tj"
        private const val TJ_ARRAY_OPERATOR = "TJ"
    }

    override fun canParse(format: String, file: File): Boolean {
        return format.lowercase() == "pdf" || file.extension.lowercase() == "pdf"
    }

    override suspend fun parseChapters(file: File): List<BookChapter> = withContext(Dispatchers.IO) {
        val chapters = mutableListOf<BookChapter>()
        var pageCount = 0

        try {
            // Guard against OOM: refuse files larger than the cap.
            if (file.length() > MAX_PDF_BYTES) {
                Log.w(TAG, "PDF file too large: ${file.length()} bytes (max $MAX_PDF_BYTES)")
                chapters.add(
                    BookChapter(
                        title = file.nameWithoutExtension.replace("_", " "),
                        content = "This PDF is too large to read (${file.length() / (1024 * 1024)} MB). " +
                            "Maximum supported size is ${MAX_PDF_BYTES / (1024 * 1024)} MB.",
                        index = 0
                    )
                )
                return@withContext chapters
            }

            val fileBytes = file.readBytes()
            pageCount = extractPageCountFromBytes(fileBytes)
            val extractedText = extractTextFromPdfBytes(fileBytes)

            if (extractedText.isNotBlank()) {
                val cleanedText = ChapterParser.cleanParagraphs(extractedText)
                val splitChapters = ChapterParser.splitIntoChapters(cleanedText)
                if (splitChapters.isNotEmpty()) {
                    chapters.addAll(splitChapters)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text from PDF", e)
        }

        if (chapters.isEmpty()) {
            val fallbackTitle = file.nameWithoutExtension.replace("_", " ").replace("-", " ")
            val fallbackContent = if (pageCount > 0) {
                "PDF document imported ($pageCount pages). The text content could not be decoded from standard text streams."
            } else {
                "PDF document imported from ${file.name}."
            }
            chapters.add(
                BookChapter(
                    title = fallbackTitle,
                    content = fallbackContent,
                    index = 0
                )
            )
        }

        chapters
    }

    override suspend fun extractMetadata(file: File): ParsedBookMetadata = withContext(Dispatchers.IO) {
        var pageCount = 0
        var title = file.nameWithoutExtension.replace("_", " ").replace("-", " ")
        var author = "Unknown Author"

        try {
            val headerText = file.inputStream().use { stream ->
                // Clamp as Long before narrowing: file.length().toInt() overflows
                // for files over 2 GB and produces a negative array size.
                val buffer = ByteArray(minOf(file.length(), 65536L).toInt())
                val read = stream.read(buffer)
                if (read > 0) String(buffer, 0, read, Charsets.ISO_8859_1) else ""
            }

            pageCount = extractPageCountFromString(headerText)

            val titleMatch = Regex("/Title\\s*\\(([^)]+)\\)").find(headerText)
            if (titleMatch != null) {
                val extractedTitle = decodePdfString(titleMatch.groupValues[1]).trim()
                if (extractedTitle.isNotBlank()) title = extractedTitle
            }

            val authorMatch = Regex("/Author\\s*\\(([^)]+)\\)").find(headerText)
            if (authorMatch != null) {
                val extractedAuthor = decodePdfString(authorMatch.groupValues[1]).trim()
                if (extractedAuthor.isNotBlank()) author = extractedAuthor
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract PDF title/author metadata", e)
        }

        ParsedBookMetadata(
            title = title,
            author = author,
            description = "PDF Document ($pageCount pages)"
        )
    }

    private fun extractPageCountFromBytes(pdfBytes: ByteArray): Int {
        val sampleSize = minOf(pdfBytes.size, 131072)
        val text = String(pdfBytes, 0, sampleSize, Charsets.ISO_8859_1)
        val count = extractPageCountFromString(text)
        if (count > 0) return count

        // Count /Type /Page occurrences across entire document if sample didn't have /Count
        val pageMarker = "/Type /Page".toByteArray(Charsets.US_ASCII)
        var countFound = 0
        var idx = 0
        while (idx < pdfBytes.size) {
            val found = indexOfByteArray(pdfBytes, pageMarker, idx)
            if (found == -1) break
            // Verify not "/Type /Pages"
            if (found + pageMarker.size < pdfBytes.size && pdfBytes[found + pageMarker.size] != 's'.code.toByte()) {
                countFound++
            }
            idx = found + pageMarker.size
        }
        return countFound
    }

    private fun extractPageCountFromString(text: String): Int {
        val countMatch = Regex("/Type\\s*/Pages[\\s\\S]*?/Count\\s+(\\d+)").find(text)
            ?: Regex("/Count\\s+(\\d+)").find(text)
        return countMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractTextFromPdfBytes(pdfBytes: ByteArray): String {
        val result = StringBuilder()
        val streamMarker = "stream".toByteArray(Charsets.US_ASCII)
        val endStreamMarker = "endstream".toByteArray(Charsets.US_ASCII)

        var searchIndex = 0
        while (searchIndex < pdfBytes.size) {
            val streamStart = indexOfByteArray(pdfBytes, streamMarker, searchIndex)
            if (streamStart == -1) break

            // Skip "stream" and any immediate whitespace / newline (\r\n or \n)
            var contentStart = streamStart + streamMarker.size
            if (contentStart < pdfBytes.size && pdfBytes[contentStart] == '\r'.code.toByte()) contentStart++
            if (contentStart < pdfBytes.size && pdfBytes[contentStart] == '\n'.code.toByte()) contentStart++

            val streamEnd = indexOfByteArray(pdfBytes, endStreamMarker, contentStart)
            if (streamEnd == -1) break

            var contentEnd = streamEnd
            // Trim trailing \r or \n before endstream
            if (contentEnd > contentStart && pdfBytes[contentEnd - 1] == '\n'.code.toByte()) contentEnd--
            if (contentEnd > contentStart && pdfBytes[contentEnd - 1] == '\r'.code.toByte()) contentEnd--

            val length = contentEnd - contentStart
            if (length > 0) {
                // P1-1: stop early once the output cap is hit — no point
                // inflating/parsing further on a low-memory device.
                if (result.length >= MAX_EXTRACTED_TEXT_CHARS) break
                val decompressed = tryDecompressFlate(pdfBytes, contentStart, length)
                val textChunk = if (decompressed != null) {
                    String(decompressed, Charsets.ISO_8859_1)
                } else {
                    String(pdfBytes, contentStart, length, Charsets.ISO_8859_1)
                }

                val extractedFromChunk = parsePdfTextOperators(textChunk)
                if (extractedFromChunk.isNotBlank()) {
                    val remaining = MAX_EXTRACTED_TEXT_CHARS - result.length
                    if (extractedFromChunk.length + 2 <= remaining) {
                        result.append(extractedFromChunk).append("\n\n")
                    } else {
                        result.append(extractedFromChunk.take(remaining.coerceAtLeast(0)))
                        break
                    }
                }
            }

            searchIndex = streamEnd + endStreamMarker.size
        }

        return result.toString().trim()
    }

    private fun tryDecompressFlate(bytes: ByteArray, offset: Int, length: Int): ByteArray? {
        val inflater = Inflater(false)
        try {
            inflater.setInput(bytes, offset, length)
            val buffer = ByteArray(4096)
            val outputStream = ByteArrayOutputStream()
            var produced = 0
            while (!inflater.finished() && produced < MAX_INFLATED_BYTES) {
                val count = inflater.inflate(buffer)
                // Zero progress with no input or dictionary requirement means the
                // stream is stuck; looping again would spin forever on IO.
                if (count == 0) break
                outputStream.write(buffer, 0, count)
                produced += count
            }
            return if (outputStream.size() > 0) outputStream.toByteArray() else null
        } catch (e: Exception) {
            // Not a FlateDecode stream or corrupted
            return null
        } finally {
            inflater.end()
        }
    }

    /**
     * Extracts the text of the content-stream string-showing operators.
     *
     * The regexes this replaced each had the shape
     * `opener(.*?)(?<!\\)closer\s*OPERATOR` — an unbounded lazy group with a
     * required suffix that may be absent. `findAll` restarts at every opener, and
     * a restart whose suffix never appears scans to the end of the chunk, so the
     * work was quadratic in a chunk the caps allow to reach [MAX_INFLATED_BYTES]
     * (32 MB), and the raw fallback in [extractTextFromPdfBytes] reaches it with
     * no compression at all. The scanners below walk the chunk once per operator
     * and cannot backtrack.
     *
     * An opener whose closer never appears ends the scan instead of restarting
     * it: if there is no unescaped `)` after offset i there is none after any
     * later offset either, so the rest of the chunk holds no complete literal.
     *
     * Two behaviours differ from the regexes on malformed input, both in favour
     * of the correct reading. A literal is no longer allowed to swallow a
     * following literal when the operator is missing (`(a) Td (b) Tj` yields `b`
     * rather than `a) Td (b`), and `\\)` now terminates a literal as it should,
     * instead of the single-backslash lookbehind treating it as an escape.
     */
    private fun parsePdfTextOperators(streamText: String): String {
        val sb = StringBuilder()
        appendTjStrings(streamText, sb)
        appendTjArrays(streamText, sb)
        appendHexTjStrings(streamText, sb)
        return sb.toString().trim()
    }

    /** `(text) Tj`. */
    private fun appendTjStrings(text: String, sb: StringBuilder) {
        var i = 0
        while (true) {
            val open = text.indexOf('(', i)
            if (open < 0) return
            val close = indexOfUnescaped(text, open + 1, ')', text.length)
            if (close < 0) return
            if (text.startsWith(TJ_OPERATOR, skipWhitespace(text, close + 1))) {
                val decoded = decodePdfString(text.substring(open + 1, close))
                if (decoded.isNotBlank()) sb.append(decoded).append(" ")
            }
            i = close + 1
        }
    }

    /** `[(text) -10 (more)] TJ`. */
    private fun appendTjArrays(text: String, sb: StringBuilder) {
        var i = 0
        // Cursor to the first ']' at or after [i]. It is reused rather than
        // re-scanned: there is no ']' between [i, close), so `close` is also the
        // first ']' after every '[' in that range. Re-scanning per '[' would make
        // a chunk of nothing but '[' with one trailing ']' quadratic again.
        var close = text.indexOf(']')
        while (true) {
            val open = text.indexOf('[', i)
            if (open < 0) return
            if (close < open) {
                close = text.indexOf(']', open + 1)
                if (close < 0) return
            }
            if (text.startsWith(TJ_ARRAY_OPERATOR, skipWhitespace(text, close + 1))) {
                appendArrayItems(text, open + 1, close, sb)
                sb.append(" ")
                i = close + 1
                close = text.indexOf(']', i)
            } else {
                // Not an array-showing operator: resume just after the '[' so a
                // nested array inside this one is still found.
                i = open + 1
            }
        }
    }

    /**
     * Appends the string and hex operands of one `TJ` array body.
     *
     * The single-pass form of `\((.*?)(?<!\\)\)|<([0-9a-fA-F]+)>`.
     */
    private fun appendArrayItems(text: String, from: Int, to: Int, sb: StringBuilder) {
        var i = from
        while (i < to) {
            when (text[i]) {
                '(' -> {
                    val close = indexOfUnescaped(text, i + 1, ')', to)
                    if (close < 0) return
                    sb.append(decodePdfString(text.substring(i + 1, close)))
                    i = close + 1
                }
                '<' -> {
                    val close = indexOfHexStringEnd(text, i + 1, to)
                    if (close < 0) {
                        i++
                    } else {
                        sb.append(decodeHexPdfString(text.substring(i + 1, close)))
                        i = close + 1
                    }
                }
                else -> i++
            }
        }
    }

    /** `<48656c6c6f> Tj`. */
    private fun appendHexTjStrings(text: String, sb: StringBuilder) {
        var i = 0
        while (true) {
            val open = text.indexOf('<', i)
            if (open < 0) return
            val close = indexOfHexStringEnd(text, open + 1, text.length)
            if (close < 0) {
                // `<<` dictionary markers and `<` inside other operators land
                // here; step past this one and keep looking.
                i = open + 1
                continue
            }
            if (text.startsWith(TJ_OPERATOR, skipWhitespace(text, close + 1))) {
                val decoded = decodeHexPdfString(text.substring(open + 1, close))
                if (decoded.isNotBlank()) sb.append(decoded).append(" ")
            }
            i = close + 1
        }
    }

    /**
     * Index of the first [closer] at or after [from], skipping any character
     * escaped by a backslash, or -1 when there is none before [limit].
     *
     * A backslash escapes the next character in a PDF literal, so `\)` does not
     * end the string while `\\)` does.
     */
    private fun indexOfUnescaped(text: String, from: Int, closer: Char, limit: Int): Int {
        var i = from
        while (i < limit) {
            val c = text[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == closer) return i
            i++
        }
        return -1
    }

    /**
     * Index of the `>` closing a hex string that starts at [from], or -1 when
     * [from] does not begin one. The body must be non-empty and all hex digits,
     * which is what keeps `<<` and `/Name` from being read as a string.
     */
    private fun indexOfHexStringEnd(text: String, from: Int, limit: Int): Int {
        var i = from
        while (i < limit && isHexDigit(text[i])) i++
        if (i == from) return -1
        return if (i < limit && text[i] == '>') i else -1
    }

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** Index of the first non-whitespace character at or after [from]. */
    private fun skipWhitespace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun decodePdfString(raw: String): String {
        return raw.replace("\\(", "(")
            .replace("\\)", ")")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\b", "\b")
            .replace("\\f", "\u000C")
            .replace("\\\\", "\\")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
    }

    private fun decodeHexPdfString(hex: String): String {
        return try {
            val cleanHex = if (hex.length % 2 != 0) hex + "0" else hex
            val bytes = cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            String(bytes, Charsets.ISO_8859_1)
        } catch (e: Exception) {
            ""
        }
    }

    private fun indexOfByteArray(source: ByteArray, target: ByteArray, fromIndex: Int): Int {
        if (target.isEmpty() || fromIndex >= source.size) return -1
        val max = source.size - target.size
        for (i in fromIndex..max) {
            var found = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }
}

