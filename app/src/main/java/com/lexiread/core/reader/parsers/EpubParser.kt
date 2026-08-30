package com.lexiread.core.reader.parsers

import android.util.Log
import android.util.Xml
import com.lexiread.core.reader.BookParser
import com.lexiread.core.reader.ChapterParser
import com.lexiread.core.reader.ParsedBookMetadata
import com.lexiread.core.util.TextEncoding
import com.lexiread.domain.model.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class EpubParser : BookParser {

    companion object {
        private const val TAG = "EpubParser"
        private const val MAX_ENTRY_SIZE_BYTES = 15 * 1024 * 1024L // 15 MB per single entry
        private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 40 * 1024 * 1024L // 40 MB total extracted text
        private const val MAX_ENTRIES_TO_PROCESS = 500
        private const val MAX_COVER_SIZE_BYTES = 5 * 1024 * 1024L // 5 MB cover cap
        /** If the uncompressed-to-compressed ratio exceeds this, the file is
         * treated as a potential zip bomb and parsing is aborted. */
        private const val ZIP_BOMB_RATIO_THRESHOLD = 100
    }

    override fun canParse(format: String, file: File): Boolean {
        return format.lowercase() == "epub" || file.extension.lowercase() == "epub"
    }

    override suspend fun parseChapters(file: File): List<BookChapter> = withContext(Dispatchers.IO) {
        val chapters = mutableListOf<BookChapter>()
        var totalBytesRead = 0L

        try {
            ZipFile(file).use { zipFile ->
                val opfPath = findOpfPath(zipFile) ?: return@withContext emptyList()
                val opfEntry = zipFile.getEntry(opfPath) ?: return@withContext emptyList()

                // Guard against large opf entry
                if (opfEntry.size > MAX_ENTRY_SIZE_BYTES) {
                    Log.w(TAG, "OPF entry exceeds size limit: ${opfEntry.size}")
                    return@withContext emptyList()
                }

                val opfBytes = readEntryBytes(zipFile, opfEntry)
                totalBytesRead += opfBytes.size
                val (manifestItems, spineItemRefs) = parseOpf(String(opfBytes, detectXmlCharset(opfBytes)))
                val baseDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

                var chapterIndex = 0
                for ((processedCount, idref) in spineItemRefs.withIndex()) {
                    if (processedCount >= MAX_ENTRIES_TO_PROCESS || totalBytesRead >= MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        Log.w(TAG, "Reached safety limits for EPUB extraction: $totalBytesRead bytes read")
                        break
                    }

                    val href = manifestItems[idref] ?: continue
                    // Some EPUBs percent-encode or relativize hrefs; normalize both attempts
                    val decodedHref = android.net.Uri.decode(href)
                    val normalizedHref = decodedHref.removePrefix("./")
                    val fullPath = (baseDir + normalizedHref).removePrefix("/")
                    val entry = zipFile.getEntry(fullPath)
                        ?: zipFile.getEntry(baseDir + decodedHref)
                        ?: zipFile.getEntry(decodedHref)
                        ?: continue

                    if (entry.size > MAX_ENTRY_SIZE_BYTES) {
                        Log.w(TAG, "Skipping oversize EPUB entry: $fullPath (${entry.size} bytes)")
                        continue
                    }

                    // Zip bomb defense: only check the ratio for entries whose
                    // uncompressed size exceeds the per-entry cap, since smaller
                    // entries are already bounded by [MAX_ENTRY_SIZE_BYTES].
                    // Highly compressible text (e.g. repeated UTF-8 characters)
                    // can legitimately achieve ratios >100, so the check must
                    // not reject valid content.
                    val compressedSize = entry.compressedSize
                    if (compressedSize > 0 && entry.size > MAX_ENTRY_SIZE_BYTES) {
                        val ratio = entry.size.toDouble() / compressedSize
                        if (ratio > ZIP_BOMB_RATIO_THRESHOLD) {
                            Log.w(TAG, "Potential zip bomb: $fullPath ratio=$ratio (compressed=$compressedSize, uncompressed=${entry.size})")
                            continue
                        }
                    }

                    // Read the whole entry and decode it in ONE pass. Decoding per
                    // 8 KiB chunk used to split multi-byte characters at buffer
                    // boundaries and litter the book with U+FFFD replacement chars.
                    val entryBytes = readEntryBytes(zipFile, entry)
                    totalBytesRead += entryBytes.size
                    if (entryBytes.isEmpty()) continue

                    val htmlContent = TextEncoding.decode(entryBytes)
                    val cleanText = ChapterParser.cleanHtmlText(htmlContent)

                    if (cleanText.isNotBlank()) {
                        val chapterTitle = extractChapterTitle(htmlContent) ?: "Chapter ${chapterIndex + 1}"
                        chapters.add(
                            BookChapter(
                                title = chapterTitle,
                                content = cleanText,
                                index = chapterIndex
                            )
                        )
                        chapterIndex++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EPUB chapters", e)
        }

        if (chapters.isEmpty()) {
            // Never show the generated filename as if it were book text. A
            // malformed EPUB must be reported to the reader as unreadable.
            return@withContext emptyList()
        }
        chapters
    }

    override suspend fun extractMetadata(file: File): ParsedBookMetadata = withContext(Dispatchers.IO) {
        var title = file.nameWithoutExtension.replace("_", " ")
        var author = "Unknown Author"
        var description: String? = null
        var coverPath: String? = null

        try {
            ZipFile(file).use { zipFile ->
                val opfPath = findOpfPath(zipFile)
                if (opfPath != null) {
                    val opfEntry = zipFile.getEntry(opfPath)
                    if (opfEntry != null) {
                        // Guard against unknown (-1) and oversize OPF entries
                        if (opfEntry.size > MAX_ENTRY_SIZE_BYTES) {
                            Log.w(TAG, "OPF entry exceeds size limit: ${opfEntry.size}")
                            return@withContext ParsedBookMetadata(
                                title = title,
                                author = author,
                                description = "Imported EPUB book.",
                                coverPath = null
                            )
                        }
                        val opfBytes = readEntryBytes(zipFile, opfEntry)
                        val meta = parseOpfMetadata(String(opfBytes, detectXmlCharset(opfBytes)))
                        if (!meta.title.isNullOrBlank()) title = meta.title
                        if (!meta.author.isNullOrBlank()) author = meta.author
                        description = meta.description

                        // Extract cover with a hard size cap to avoid OOM on malicious files
                        if (!meta.coverHref.isNullOrBlank()) {
                            val baseDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""
                            val fullCoverPath = baseDir + meta.coverHref
                            val coverEntry = zipFile.getEntry(fullCoverPath) ?: zipFile.getEntry(meta.coverHref)
                            if (coverEntry != null && coverEntry.size in 1L..MAX_COVER_SIZE_BYTES) {
                                val coverBytes = zipFile.getInputStream(coverEntry).use { ins ->
                                    ins.readNBytes(MAX_COVER_SIZE_BYTES.toInt() + 1)
                                }
                                if (coverBytes.size <= MAX_COVER_SIZE_BYTES) {
                                    val coverFile = File(file.parentFile, "${file.nameWithoutExtension}_cover.jpg")
                                    FileOutputStream(coverFile).use { fos ->
                                        fos.write(coverBytes)
                                    }
                                    coverPath = coverFile.absolutePath
                                } else {
                                    Log.w(TAG, "Skipping oversize EPUB cover")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting EPUB metadata", e)
        }

        ParsedBookMetadata(
            title = title,
            author = author,
            description = description ?: "Imported EPUB book.",
            coverPath = coverPath
        )
    }

    /**
     * Reads an entry into memory with the global per-entry cap applied.
     * Unknown declared sizes (-1) are still capped by [TextEncoding.readCappedBytes].
     */
    private fun readEntryBytes(zipFile: ZipFile, entry: ZipEntry): ByteArray {
        return zipFile.getInputStream(entry).use { stream ->
            TextEncoding.readCappedBytes(stream, MAX_ENTRY_SIZE_BYTES)
        }
    }

    /**
     * XML documents declare their own encoding. Honour the prolog instead of
     * assuming UTF-8, otherwise windows-1251 books decode into mojibake.
     */
    private fun detectXmlCharset(bytes: ByteArray): Charset =
        TextEncoding.detectCharset(bytes)

    private fun findOpfPath(zipFile: ZipFile): String? {
        val containerEntry = zipFile.getEntry("META-INF/container.xml") ?: return null
        val containerBytes = readEntryBytes(zipFile, containerEntry)
        if (containerBytes.isEmpty()) return null

        newPullParser(String(containerBytes, detectXmlCharset(containerBytes))).let { parser ->
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    val fullPath = parser.getAttributeValue(null, "full-path")
                    if (!fullPath.isNullOrEmpty()) {
                        return fullPath
                    }
                }
                eventType = parser.next()
            }
        }
        return null
    }

    /**
     * Parsing from a decoded String keeps the declared encoding intact.
     * `setInput(stream, "UTF-8")` would re-introduce the mojibake bug here.
     */
    private fun newPullParser(document: String): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(document))
        return parser
    }

    private data class OpfMetadata(
        val title: String?,
        val author: String?,
        val description: String?,
        val coverHref: String?
    )

    private fun parseOpfMetadata(document: String): OpfMetadata {
        var title: String? = null
        var author: String? = null
        var description: String? = null
        var coverId: String? = null
        var coverHref: String? = null
        val manifest = mutableMapOf<String, String>()

        val parser = newPullParser(document)

        var eventType = parser.eventType
        var currentTag = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "item") {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val properties = parser.getAttributeValue(null, "properties")
                        if (id != null && href != null) {
                            manifest[id] = href
                            if (properties?.contains("cover-image") == true) {
                                coverHref = href
                            }
                        }
                    } else if (currentTag == "meta") {
                        val name = parser.getAttributeValue(null, "name")
                        val content = parser.getAttributeValue(null, "content")
                        if (name == "cover" && content != null) {
                            coverId = content
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        when (currentTag.lowercase()) {
                            "dc:title", "title" -> title = text
                            "dc:creator", "creator" -> author = text
                            "dc:description", "description" -> description = text
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        if (coverHref == null && coverId != null) {
            coverHref = manifest[coverId]
        }

        return OpfMetadata(title, author, description, coverHref)
    }

    private fun parseOpf(document: String): Pair<Map<String, String>, List<String>> {
        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()

        val parser = newPullParser(document)

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (id != null && href != null) {
                            manifest[id] = href
                        }
                    }
                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref")
                        if (idref != null) {
                            spine.add(idref)
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return Pair(manifest, spine)
    }

    private fun extractChapterTitle(htmlText: String): String? {
        val h1Regex = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", RegexOption.IGNORE_CASE)
        val match = h1Regex.find(htmlText)
        if (match != null) {
            val title = ChapterParser.cleanHtmlText(match.groupValues[1])
            if (title.isNotBlank()) return title
        }
        val titleTagRegex = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
        val titleMatch = titleTagRegex.find(htmlText)
        if (titleMatch != null) {
            val title = ChapterParser.cleanHtmlText(titleMatch.groupValues[1])
            if (title.isNotBlank() && !title.contains(".html", ignoreCase = true)) return title
        }
        return null
    }
}
