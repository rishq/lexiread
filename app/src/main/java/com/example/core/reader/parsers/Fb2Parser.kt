package com.example.core.reader.parsers

import android.util.Base64
import android.util.Xml
import com.example.core.reader.BookParser
import com.example.core.reader.ChapterParser
import com.example.core.reader.ParsedBookMetadata
import com.example.domain.model.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader

class Fb2Parser : BookParser {

    override fun canParse(format: String, file: File): Boolean {
        return format.lowercase() == "fb2" || file.extension.lowercase() == "fb2"
    }

    override suspend fun parseChapters(file: File): List<BookChapter> = withContext(Dispatchers.IO) {
        val text = file.readText(Charsets.UTF_8)
        val chapters = mutableListOf<BookChapter>()

        try {
            val sectionRegex = Regex("<section[\\s\\S]*?>([\\s\\S]*?)</section>", RegexOption.IGNORE_CASE)
            val titleRegex = Regex("<title[\\s\\S]*?>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
            val pRegex = Regex("<p[\\s\\S]*?>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE)

            var sectionIndex = 0
            for (match in sectionRegex.findAll(text)) {
                val sectionXml = match.groupValues[1]
                val titleMatch = titleRegex.find(sectionXml)
                val rawTitle = titleMatch?.groupValues?.get(1)?.let { ChapterParser.cleanHtmlText(it) }
                val title = if (!rawTitle.isNullOrBlank()) rawTitle else "Section ${sectionIndex + 1}"

                val paragraphs = pRegex.findAll(sectionXml)
                    .map { ChapterParser.cleanHtmlText(it.groupValues[1]) }
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")

                if (paragraphs.isNotBlank()) {
                    chapters.add(
                        BookChapter(
                            title = title,
                            content = paragraphs,
                            index = sectionIndex
                        )
                    )
                    sectionIndex++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (chapters.isEmpty()) {
            val cleanAll = ChapterParser.cleanHtmlText(text)
            return@withContext ChapterParser.splitIntoChapters(cleanAll)
        }

        chapters
    }

    override suspend fun extractMetadata(file: File): ParsedBookMetadata = withContext(Dispatchers.IO) {
        var title = file.nameWithoutExtension.replace("_", " ")
        var author = "Unknown Author"
        var description: String? = null
        var coverPath: String? = null

        try {
            val text = file.readText(Charsets.UTF_8)
            val titleMatch = Regex("<book-title[\\s\\S]*?>([\\s\\S]*?)</book-title>", RegexOption.IGNORE_CASE).find(text)
            if (titleMatch != null) {
                val t = ChapterParser.cleanHtmlText(titleMatch.groupValues[1])
                if (t.isNotBlank()) title = t
            }

            val authorFirstName = Regex("<first-name[\\s\\S]*?>([\\s\\S]*?)</first-name>", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            val authorLastName = Regex("<last-name[\\s\\S]*?>([\\s\\S]*?)</last-name>", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            if (!authorFirstName.isNullOrBlank() || !authorLastName.isNullOrBlank()) {
                author = "${authorFirstName.orEmpty()} ${authorLastName.orEmpty()}".trim()
            }

            val descMatch = Regex("<annotation[\\s\\S]*?>([\\s\\S]*?)</annotation>", RegexOption.IGNORE_CASE).find(text)
            if (descMatch != null) {
                description = ChapterParser.cleanHtmlText(descMatch.groupValues[1])
            }

            // Extract binary cover page
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
            e.printStackTrace()
        }

        ParsedBookMetadata(
            title = title,
            author = author,
            description = description ?: "Imported FB2 book.",
            coverPath = coverPath
        )
    }
}
