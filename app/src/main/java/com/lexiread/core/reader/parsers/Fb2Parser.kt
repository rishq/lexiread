package com.lexiread.core.reader.parsers

import android.util.Base64
import android.util.Log
import com.lexiread.core.reader.BookParser
import com.lexiread.core.reader.ChapterParser
import com.lexiread.core.reader.ParsedBookMetadata
import com.lexiread.core.util.SafeXml
import com.lexiread.core.util.TextEncoding
import com.lexiread.domain.model.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader

class Fb2Parser : BookParser {

    companion object {
        private const val TAG = "Fb2Parser"
        private const val MAX_FILE_SIZE_BYTES = 40L * 1024 * 1024
        // P1-2: same 5 MB cover cap as EpubParser — a malicious <binary> must
        // not OOM the decode or fill filesDir unbounded.
        private const val MAX_COVER_SIZE_BYTES = 5L * 1024 * 1024
        private const val MAX_BASE64_CHARS = (MAX_COVER_SIZE_BYTES * 4 / 3 + 4)
        private const val BINARY_OPEN_TAG = "<binary"
        private const val BINARY_CLOSE_TAG = "</binary"

        /** The `id="..."` attribute of a `<binary>` start tag, matched against
         * the tag text only so `data-id` cannot satisfy it. */
        private val BINARY_ID_ATTRIBUTE = Regex("(?:^|\\s)id=\"[^\"]+\"", RegexOption.IGNORE_CASE)

        /**
         * Returns the raw text of the first `<binary id="...">` element, or null.
         *
         * This replaced a regex whose payload group was a lazy
         * `([\s\S]*?)</binary>`. `Regex.find` restarts at every `<binary id=`
         * occurrence and each restart scanned forward to the end of the document
         * when the closing tag was missing, so a document built from repeated
         * openers and no closer — well inside the [MAX_FILE_SIZE_BYTES] cap — cost
         * quadratic work on the import thread. An index scan visits each
         * `<binary` and each `</binary` once, so the cost is linear in the
         * document length.
         */
        private fun findFirstBinaryPayload(text: String): String? {
            var searchFrom = 0
            while (true) {
                val open = text.indexOf(BINARY_OPEN_TAG, searchFrom, ignoreCase = true)
                if (open < 0) return null
                val tagEnd = text.indexOf('>', open)
                if (tagEnd < 0) return null
                val openTag = text.substring(open, tagEnd)
                searchFrom = tagEnd + 1
                if (!BINARY_ID_ATTRIBUTE.containsMatchIn(openTag)) continue
                val close = text.indexOf(BINARY_CLOSE_TAG, tagEnd, ignoreCase = true)
                if (close < 0) return null
                return text.substring(tagEnd + 1, close)
            }
        }

        /**
         * Removes XML whitespace from [payload], returning null once the result
         * would exceed [MAX_BASE64_CHARS].
         *
         * The cap is enforced while building rather than afterwards, so an
         * oversize `<binary>` never allocates the stripped copy.
         */
        private fun stripBase64Whitespace(payload: CharSequence): String? {
            val out = StringBuilder(minOf(payload.length, MAX_BASE64_CHARS.toInt()))
            for (ch in payload) {
                if (ch.isWhitespace()) continue
                if (out.length.toLong() >= MAX_BASE64_CHARS) return null
                out.append(ch)
            }
            return out.toString()
        }
    }

    /** Reads an FB2 file honouring its declared encoding instead of assuming UTF-8. */
    private fun readDocument(file: File): String =
        file.inputStream().use { TextEncoding.readText(it, MAX_FILE_SIZE_BYTES) }

    override fun canParse(format: String, file: File): Boolean {
        return format.lowercase() == "fb2" || file.extension.lowercase() == "fb2"
    }

    override suspend fun parseChapters(file: File): List<BookChapter> = withContext(Dispatchers.IO) {
        val chapters = mutableListOf<BookChapter>()

        try {
            val fileText = readDocument(file)
            // An internal DTD subset is the only way this document could declare
            // entities for the parser to expand; see SafeXml for the detail.
            SafeXml.requireNoInternalDtdSubset(fileText)
            val parser = SafeXml.newPullParser()
            parser.setInput(StringReader(fileText))

            var eventType = parser.eventType
            var inBody = false
            var inSection = false
            var inTitle = false
            var inParagraph = false

            var currentSectionTitle: String? = null
            val currentSectionParagraphs = mutableListOf<String>()
            val currentTitleBuffer = StringBuilder()
            val currentParagraphBuffer = StringBuilder()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.lowercase()) {
                            "body" -> inBody = true
                            "section" -> {
                                if (inBody) {
                                    inSection = true
                                    currentSectionTitle = null
                                    currentSectionParagraphs.clear()
                                }
                            }
                            "title" -> {
                                if (inSection) {
                                    inTitle = true
                                    currentTitleBuffer.clear()
                                }
                            }
                            "p" -> {
                                inParagraph = true
                                currentParagraphBuffer.clear()
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text.orEmpty()
                        if (inTitle) {
                            currentTitleBuffer.append(text)
                        } else if (inParagraph) {
                            currentParagraphBuffer.append(text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name.lowercase()) {
                            "title" -> {
                                inTitle = false
                                val titleStr = currentTitleBuffer.toString().trim()
                                if (titleStr.isNotBlank()) {
                                    currentSectionTitle = titleStr
                                }
                            }
                            "p" -> {
                                inParagraph = false
                                val pStr = currentParagraphBuffer.toString().trim()
                                if (pStr.isNotBlank()) {
                                    currentSectionParagraphs.add(pStr)
                                }
                            }
                            "section" -> {
                                inSection = false
                                if (currentSectionParagraphs.isNotEmpty()) {
                                    val title = currentSectionTitle ?: "Section ${chapters.size + 1}"
                                    val content = currentSectionParagraphs.joinToString("\n\n")
                                    chapters.add(
                                        BookChapter(
                                            title = title,
                                            content = content,
                                            index = chapters.size
                                        )
                                    )
                                }
                                currentSectionParagraphs.clear()
                                currentSectionTitle = null
                            }
                            "body" -> inBody = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing FB2 with XmlPullParser, using regex fallback", e)
            try {
                val text = readDocument(file)
                val cleanAll = ChapterParser.cleanHtmlText(text)
                val split = ChapterParser.splitIntoChapters(cleanAll)
                if (split.isNotEmpty()) {
                    return@withContext split
                }
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Fallback chapter splitting also failed", fallbackEx)
            }
        }

        if (chapters.isEmpty()) {
            val cleanAll = try {
                val text = readDocument(file)
                ChapterParser.cleanHtmlText(text)
            } catch (e: Exception) {
                file.nameWithoutExtension
            }
            return@withContext ChapterParser.splitIntoChapters(cleanAll)
        }

        chapters
    }

    override suspend fun extractMetadata(file: File): ParsedBookMetadata = withContext(Dispatchers.IO) {
        var title = file.nameWithoutExtension.replace("_", " ").replace("-", " ")
        var author = "Unknown Author"
        var description: String? = null
        var coverPath: String? = null

        try {
            val text = readDocument(file)
            SafeXml.requireNoInternalDtdSubset(text)
            val parser = SafeXml.newPullParser()
            parser.setInput(StringReader(text))

            var eventType = parser.eventType
            var currentTag = ""
            var authorFirstName = ""
            var authorLastName = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.lowercase()
                    }
                    XmlPullParser.TEXT -> {
                        val value = parser.text?.trim().orEmpty()
                        if (value.isNotEmpty()) {
                            when (currentTag) {
                                "book-title" -> if (title == file.nameWithoutExtension.replace("_", " ").replace("-", " ")) title = value
                                "first-name" -> authorFirstName = value
                                "last-name" -> authorLastName = value
                                "annotation" -> if (description == null) description = value
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("author", ignoreCase = true)) {
                            val combinedAuthor = "$authorFirstName $authorLastName".trim()
                            if (combinedAuthor.isNotBlank()) {
                                author = combinedAuthor
                            }
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }

            // Extract binary cover if present (P1-2: capped at 5 MB like EPUB).
            val binaryPayload = findFirstBinaryPayload(text)
            if (binaryPayload != null) {
                // Whitespace is stripped and the length checked in a single pass,
                // so a huge <binary> is rejected without allocating the decoded
                // buffer. Base64 inflates ~4/3.
                val base64Data = stripBase64Whitespace(binaryPayload)
                if (base64Data == null) {
                    Log.w(TAG, "Skipping oversize FB2 cover (>$MAX_BASE64_CHARS base64 chars)")
                } else {
                    val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    if (imageBytes.size.toLong() > MAX_COVER_SIZE_BYTES) {
                        Log.w(TAG, "Skipping oversize FB2 cover (${imageBytes.size} bytes)")
                    } else {
                        val coverFile = File(file.parentFile, "${file.nameWithoutExtension}_cover.jpg")
                        FileOutputStream(coverFile).use { fos ->
                            fos.write(imageBytes)
                        }
                        coverPath = coverFile.absolutePath
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse FB2 metadata", e)
        }

        ParsedBookMetadata(
            title = title,
            author = author,
            description = description ?: "Imported FB2 book.",
            coverPath = coverPath
        )
    }
}

