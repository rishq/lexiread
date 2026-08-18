package com.example.core.reader.parsers

import android.util.Log
import com.example.core.reader.BookParser
import com.example.core.reader.ChapterParser
import com.example.core.reader.ParsedBookMetadata
import com.example.domain.model.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

class PdfParser : BookParser {

    companion object {
        private const val TAG = "PdfParser"
        private const val MAX_STREAM_DECOMPRESSED_BYTES = 10 * 1024 * 1024 // 10 MB per stream
        private const val MAX_TOTAL_PDF_TEXT_BYTES = 30 * 1024 * 1024 // 30 MB total extracted text
    }

    override fun canParse(format: String, file: File): Boolean {
        return format.lowercase() == "pdf" || file.extension.lowercase() == "pdf"
    }

    override suspend fun parseChapters(file: File): List<BookChapter> = withContext(Dispatchers.IO) {
        val chapters = mutableListOf<BookChapter>()
        var pageCount = 0

        try {
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
                val buffer = ByteArray(minOf(file.length().toInt(), 65536))
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
        var totalExtractedBytes = 0

        while (searchIndex < pdfBytes.size && totalExtractedBytes < MAX_TOTAL_PDF_TEXT_BYTES) {
            val streamStart = indexOfByteArray(pdfBytes, streamMarker, searchIndex)
            if (streamStart == -1) break

            var contentStart = streamStart + streamMarker.size
            if (contentStart < pdfBytes.size && pdfBytes[contentStart] == '\r'.code.toByte()) contentStart++
            if (contentStart < pdfBytes.size && pdfBytes[contentStart] == '\n'.code.toByte()) contentStart++

            val streamEnd = indexOfByteArray(pdfBytes, endStreamMarker, contentStart)
            if (streamEnd == -1) break

            var contentEnd = streamEnd
            if (contentEnd > contentStart && pdfBytes[contentEnd - 1] == '\n'.code.toByte()) contentEnd--
            if (contentEnd > contentStart && pdfBytes[contentEnd - 1] == '\r'.code.toByte()) contentEnd--

            val length = contentEnd - contentStart
            if (length > 0) {
                val decompressed = tryDecompressFlate(pdfBytes, contentStart, length)
                val textChunk = if (decompressed != null) {
                    String(decompressed, Charsets.ISO_8859_1)
                } else {
                    String(pdfBytes, contentStart, length, Charsets.ISO_8859_1)
                }

                val extractedFromChunk = parsePdfTextOperators(textChunk)
                if (extractedFromChunk.isNotBlank()) {
                    result.append(extractedFromChunk).append("\n\n")
                    totalExtractedBytes += extractedFromChunk.length
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
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                outputStream.write(buffer, 0, count)
                if (outputStream.size() > MAX_STREAM_DECOMPRESSED_BYTES) {
                    Log.w(TAG, "Stream exceeds max decompressed size limit")
                    break
                }
            }
            return if (outputStream.size() > 0) outputStream.toByteArray() else null
        } catch (e: Exception) {
            return null
        } finally {
            inflater.end()
        }
    }

    // Fast linear scanner for PDF Tj and TJ text operators (ReDoS-immune)
    private fun parsePdfTextOperators(streamText: String): String {
        val sb = StringBuilder()
        var i = 0
        val len = streamText.length

        while (i < len) {
            val c = streamText[i]
            if (c == '(') {
                // Parse literal string (...)
                val strBuilder = StringBuilder()
                i++
                var escaped = false
                var depth = 1
                while (i < len && depth > 0) {
                    val sc = streamText[i]
                    if (escaped) {
                        strBuilder.append(sc)
                        escaped = false
                    } else if (sc == '\\') {
                        escaped = true
                    } else if (sc == '(') {
                        depth++
                        strBuilder.append(sc)
                    } else if (sc == ')') {
                        depth--
                        if (depth > 0) strBuilder.append(sc)
                    } else {
                        strBuilder.append(sc)
                    }
                    i++
                }

                // Check if followed by Tj
                var nextNonSpace = i
                while (nextNonSpace < len && streamText[nextNonSpace].isWhitespace()) nextNonSpace++
                if (nextNonSpace + 1 < len && streamText[nextNonSpace] == 'T' && streamText[nextNonSpace + 1] == 'j') {
                    val decoded = decodePdfString(strBuilder.toString())
                    if (decoded.isNotBlank()) sb.append(decoded).append(" ")
                    i = nextNonSpace + 2
                }
            } else if (c == '<' && i + 1 < len && streamText[i + 1] != '<') {
                // Parse hex string <...>
                val hexStart = i + 1
                val hexEnd = streamText.indexOf('>', hexStart)
                if (hexEnd != -1) {
                    val hexStr = streamText.substring(hexStart, hexEnd)
                    var nextNonSpace = hexEnd + 1
                    while (nextNonSpace < len && streamText[nextNonSpace].isWhitespace()) nextNonSpace++
                    if (nextNonSpace + 1 < len && streamText[nextNonSpace] == 'T' && streamText[nextNonSpace + 1] == 'j') {
                        val decoded = decodeHexPdfString(hexStr)
                        if (decoded.isNotBlank()) sb.append(decoded).append(" ")
                        i = nextNonSpace + 2
                    } else {
                        i = hexEnd + 1
                    }
                } else {
                    i++
                }
            } else {
                i++
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

    // Boyer-Moore-Horspool fast substring search on byte arrays
    private fun indexOfByteArray(source: ByteArray, target: ByteArray, fromIndex: Int): Int {
        if (target.isEmpty() || fromIndex >= source.size) return -1
        val m = target.size
        val n = source.size
        if (fromIndex + m > n) return -1

        // Precompute shift table (bad character table)
        val shiftTable = IntArray(256) { m }
        for (i in 0 until m - 1) {
            val byteVal = target[i].toInt() and 0xFF
            shiftTable[byteVal] = m - 1 - i
        }

        var k = fromIndex + m - 1
        while (k < n) {
            var j = m - 1
            var i = k
            while (j >= 0 && source[i] == target[j]) {
                i--
                j--
            }
            if (j < 0) {
                return i + 1
            }
            val lastByte = source[k].toInt() and 0xFF
            k += shiftTable[lastByte]
        }
        return -1
    }
}

