package com.lexiread.core.reader.parsers

import android.util.Base64
import android.util.Log
import android.util.Xml
import com.lexiread.core.reader.BookParser
import com.lexiread.core.reader.ChapterParser
import com.lexiread.core.reader.ParsedBookMetadata
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
    }

    override fun canParse(format: String, file: File): Boolean {
        return format.lowercase() == "fb2" || file.extension.lowercase() == "fb2"
    }

    override suspend fun parseChapters(file: File): List<BookChapter> = withContext(Dispatchers.IO) {
        val chapters = mutableListOf<BookChapter>()

        try {
            val fileText = file.readText(Charsets.UTF_8)
            val parser = Xml.newPullParser()
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
                val text = file.readText(Charsets.UTF_8)
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
                val text = file.readText(Charsets.UTF_8)
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
            val text = file.readText(Charsets.UTF_8)
            val parser = Xml.newPullParser()
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

            // Extract binary cover if present
            val binaryMatch = Regex("<binary[^>]*id=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</binary>", RegexOption.IGNORE_CASE).find(text)
            if (binaryMatch != null) {
                val base64Data = binaryMatch.groupValues[2].replace("\\s".toRegex(), "")
                val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                val coverFile = File(file.parentFile, "${file.nameWithoutExtension}_cover.jpg")
                FileOutputStream(coverFile).use { fos ->
                    fos.write(imageBytes)
                }
                coverPath = coverFile.absolutePath
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

