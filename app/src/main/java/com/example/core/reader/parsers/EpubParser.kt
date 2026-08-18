package com.example.core.reader.parsers

import android.util.Log
import android.util.Xml
import com.example.core.reader.BookParser
import com.example.core.reader.ChapterParser
import com.example.core.reader.ParsedBookMetadata
import com.example.domain.model.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class EpubParser : BookParser {

    companion object {
        private const val TAG = "EpubParser"
        private const val MAX_ENTRY_SIZE_BYTES = 15 * 1024 * 1024L // 15 MB per single entry
        private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 40 * 1024 * 1024L // 40 MB total extracted text
        private const val MAX_ENTRIES_TO_PROCESS = 500
    }

    override fun canParse(format: String, file: File): Boolean {
        return format.lowercase() == "epub" || file.extension.lowercase() == "epub"
    }

    override suspend fun parseChapters(file: File): List<BookChapter> = withContext(Dispatchers.IO) {
        val chapters = mutableListOf<BookChapter>()
        var totalBytesRead = 0L

        try {
            val zipFile = ZipFile(file)
            val opfPath = findOpfPath(zipFile) ?: return@withContext emptyList()
            val opfEntry = zipFile.getEntry(opfPath) ?: return@withContext emptyList()

            // Guard against large opf entry
            if (opfEntry.size > MAX_ENTRY_SIZE_BYTES) {
                Log.w(TAG, "OPF entry exceeds size limit: ${opfEntry.size}")
                zipFile.close()
                return@withContext emptyList()
            }
            
            val (manifestItems, spineItemRefs) = parseOpf(zipFile.getInputStream(opfEntry))
            val baseDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

            var chapterIndex = 0
            for ((processedCount, idref) in spineItemRefs.withIndex()) {
                if (processedCount >= MAX_ENTRIES_TO_PROCESS || totalBytesRead >= MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    Log.w(TAG, "Reached safety limits for EPUB extraction: $totalBytesRead bytes read")
                    break
                }

                val href = manifestItems[idref] ?: continue
                val fullPath = baseDir + href
                val entry = zipFile.getEntry(fullPath) ?: zipFile.getEntry(href) ?: continue

                if (entry.size > MAX_ENTRY_SIZE_BYTES) {
                    Log.w(TAG, "Skipping oversize EPUB entry: $fullPath (${entry.size} bytes)")
                    continue
                }
                
                val htmlContent = zipFile.getInputStream(entry).use { inputStream ->
                    val buffer = ByteArray(8192)
                    val out = StringBuilder()
                    var bytesForThisEntry = 0L
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        bytesForThisEntry += read
                        totalBytesRead += read
                        if (bytesForThisEntry > MAX_ENTRY_SIZE_BYTES || totalBytesRead > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                            break
                        }
                        out.append(String(buffer, 0, read, Charsets.UTF_8))
                    }
                    out.toString()
                }

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
            zipFile.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EPUB chapters", e)
        }

        if (chapters.isEmpty()) {
            val fallbackText = file.nameWithoutExtension
            return@withContext listOf(BookChapter("Chapter 1", fallbackText, 0))
        }
        chapters
    }

    override suspend fun extractMetadata(file: File): ParsedBookMetadata = withContext(Dispatchers.IO) {
        var title = file.nameWithoutExtension.replace("_", " ")
        var author = "Unknown Author"
        var description: String? = null
        var coverPath: String? = null

        try {
            val zipFile = ZipFile(file)
            val opfPath = findOpfPath(zipFile)
            if (opfPath != null) {
                val opfEntry = zipFile.getEntry(opfPath)
                if (opfEntry != null) {
                    val isStream = zipFile.getInputStream(opfEntry)
                    val bytes = isStream.readBytes()
                    
                    val meta = parseOpfMetadata(ByteArrayInputStream(bytes))
                    if (!meta.title.isNullOrBlank()) title = meta.title
                    if (!meta.author.isNullOrBlank()) author = meta.author
                    description = meta.description

                    // Extract cover
                    if (!meta.coverHref.isNullOrBlank()) {
                        val baseDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""
                        val fullCoverPath = baseDir + meta.coverHref
                        val coverEntry = zipFile.getEntry(fullCoverPath) ?: zipFile.getEntry(meta.coverHref)
                        if (coverEntry != null) {
                            val coverBytes = zipFile.getInputStream(coverEntry).readBytes()
                            val coverFile = File(file.parentFile, "${file.nameWithoutExtension}_cover.jpg")
                            FileOutputStream(coverFile).use { fos ->
                                fos.write(coverBytes)
                            }
                            coverPath = coverFile.absolutePath
                        }
                    }
                }
            }
            zipFile.close()
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


    private fun findOpfPath(zipFile: ZipFile): String? {
        val containerEntry = zipFile.getEntry("META-INF/container.xml") ?: return null
        val stream = zipFile.getInputStream(containerEntry)
        val parser = Xml.newPullParser()
        parser.setInput(stream, "UTF-8")

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
        return null
    }

    private data class OpfMetadata(
        val title: String?,
        val author: String?,
        val description: String?,
        val coverHref: String?
    )

    private fun parseOpfMetadata(inputStream: InputStream): OpfMetadata {
        var title: String? = null
        var author: String? = null
        var description: String? = null
        var coverId: String? = null
        var coverHref: String? = null
        val manifest = mutableMapOf<String, String>()

        val parser = Xml.newPullParser()
        parser.setInput(inputStream, "UTF-8")

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

    private fun parseOpf(inputStream: InputStream): Pair<Map<String, String>, List<String>> {
        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()

        val parser = Xml.newPullParser()
        parser.setInput(inputStream, "UTF-8")

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
