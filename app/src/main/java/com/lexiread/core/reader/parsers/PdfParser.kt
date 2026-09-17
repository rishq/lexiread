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

    private fun parsePdfTextOperators(streamText: String): String {
        val sb = StringBuilder()

        // Match Tj operator: (text) Tj
        val tjRegex = Regex("\\((.*?)(?<!\\\\)\\)\\s*Tj", RegexOption.DOT_MATCHES_ALL)
        for (match in tjRegex.findAll(streamText)) {
            val text = decodePdfString(match.groupValues[1])
            if (text.isNotBlank()) {
                sb.append(text).append(" ")
            }
        }

        // Match TJ array operator: [(text) -10 (more)] TJ
        val tjArrayRegex = Regex("\\[(.*?)\\]\\s*TJ", RegexOption.DOT_MATCHES_ALL)
        val innerStringRegex = Regex("\\((.*?)(?<!\\\\)\\)|<([0-9a-fA-F]+)>", RegexOption.DOT_MATCHES_ALL)
        for (match in tjArrayRegex.findAll(streamText)) {
            val arrayContent = match.groupValues[1]
            for (item in innerStringRegex.findAll(arrayContent)) {
                val str = item.groupValues[1]
                val hex = item.groupValues[2]
                if (str.isNotEmpty()) {
                    sb.append(decodePdfString(str))
                } else if (hex.isNotEmpty()) {
                    sb.append(decodeHexPdfString(hex))
                }
            }
            sb.append(" ")
        }

        // Match Hex string Tj: <48656c6c6f> Tj
        val hexTjRegex = Regex("<([0-9a-fA-F]+)>\\s*Tj")
        for (match in hexTjRegex.findAll(streamText)) {
            val hex = match.groupValues[1]
            val text = decodeHexPdfString(hex)
            if (text.isNotBlank()) {
                sb.append(text).append(" ")
            }
        }

        return sb.toString().trim()
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

