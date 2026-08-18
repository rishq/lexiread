package com.example.core.reader

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.domain.model.BookChapter
import com.example.domain.model.ReaderPage
import com.example.domain.model.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PaginationEngine(private val context: Context) {

    suspend fun paginateChapter(
        chapter: BookChapter,
        settings: ReaderSettings,
        availableWidthPx: Int,
        availableHeightPx: Int
    ): List<ReaderPage> = withContext(Dispatchers.Default) {
        if (chapter.content.isBlank() || availableWidthPx <= 100 || availableHeightPx <= 100) {
            return@withContext listOf(
                ReaderPage(
                    chapterIndex = chapter.index,
                    pageIndex = 0,
                    totalPagesInChapter = 1,
                    text = chapter.content.ifBlank { "Empty chapter." },
                    chapterTitle = chapter.title
                )
            )
        }

        val density = context.resources.displayMetrics.density
        val marginPx = (settings.marginDp * density).toInt()
        val usableWidth = (availableWidthPx - (marginPx * 2)).coerceAtLeast(200)
        val usableHeight = (availableHeightPx - (marginPx * 2) - (48 * density).toInt()).coerceAtLeast(200)

        val textPaint = TextPaint().apply {
            textSize = settings.fontSizeSp * density
            isAntiAlias = true
            typeface = when (settings.fontFamilyName.lowercase()) {
                "serif" -> Typeface.SERIF
                "sans-serif", "sans" -> Typeface.SANS_SERIF
                "monospace", "mono" -> Typeface.MONOSPACE
                else -> Typeface.SERIF
            }
        }

        val text = chapter.content
        val pagesText = mutableListOf<String>()

        var startOffset = 0
        val length = text.length

        while (startOffset < length) {
            val endOffset = findPageEndOffset(
                text = text,
                startOffset = startOffset,
                usableWidth = usableWidth,
                usableHeight = usableHeight,
                textPaint = textPaint,
                spacingMult = settings.lineHeightMultiplier
            )

            if (endOffset <= startOffset) {
                val safeOffset = (startOffset + 100).coerceAtMost(length)
                pagesText.add(text.substring(startOffset, safeOffset))
                startOffset = safeOffset
            } else {
                pagesText.add(text.substring(startOffset, endOffset).trim())
                startOffset = endOffset
            }
        }

        val totalPages = pagesText.size.coerceAtLeast(1)
        pagesText.mapIndexed { index, pageText ->
            ReaderPage(
                chapterIndex = chapter.index,
                pageIndex = index,
                totalPagesInChapter = totalPages,
                text = pageText,
                chapterTitle = chapter.title
            )
        }
    }

    private fun findPageEndOffset(
        text: String,
        startOffset: Int,
        usableWidth: Int,
        usableHeight: Int,
        textPaint: TextPaint,
        spacingMult: Float
    ): Int {
        if (startOffset >= text.length) return startOffset

        // Optimize: Use a sliding chunk window (~3500 chars) instead of measuring the entire remainder of the chapter
        val maxWindowSize = 3500
        val windowEnd = (startOffset + maxWindowSize).coerceAtMost(text.length)
        val windowText = text.substring(startOffset, windowEnd)

        val staticLayout = createStaticLayout(
            text = windowText,
            paint = textPaint,
            width = usableWidth,
            spacingMult = spacingMult
        )

        val lineCount = staticLayout.lineCount
        var fittingLines = 0

        for (i in 0 until lineCount) {
            val lineBottom = staticLayout.getLineBottom(i)
            if (lineBottom <= usableHeight) {
                fittingLines++
            } else {
                break
            }
        }

        if (fittingLines == 0) {
            fittingLines = 1
        }

        val lineEndOffset = staticLayout.getLineEnd(fittingLines - 1)
        var actualEndOffset = startOffset + lineEndOffset

        if (actualEndOffset < text.length) {
            val spaceBefore = text.lastIndexOf(' ', actualEndOffset)
            val newlineBefore = text.lastIndexOf('\n', actualEndOffset)
            val breakPoint = maxOf(spaceBefore, newlineBefore)

            if (breakPoint > startOffset + (lineEndOffset / 2)) {
                actualEndOffset = breakPoint + 1
            }
        }

        return actualEndOffset.coerceAtMost(text.length)
    }

    private fun createStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        spacingMult: Float
    ): StaticLayout {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, spacingMult)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                text, paint, width, Layout.Alignment.ALIGN_NORMAL, spacingMult, 0f, false
            )
        }
    }
}
