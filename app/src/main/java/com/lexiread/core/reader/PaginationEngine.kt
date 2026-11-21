package com.lexiread.core.reader

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.lexiread.domain.model.BookChapter
import com.lexiread.domain.model.ReaderPage
import com.lexiread.domain.model.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Pagination contract the reader depends on.
 *
 * [ReaderViewModel] talks to this instead of the concrete engine so tests can
 * supply a deterministic paginator. That matters more than usual here:
 * Robolectric fakes text metrics, so the real engine collapses any chapter to a
 * single page no matter the viewport — a test asserting "a resize repaginated"
 * could never fail, and therefore never proved anything.
 */
interface Paginator {
    suspend fun paginateChapter(
        chapter: BookChapter,
        settings: ReaderSettings,
        availableWidthPx: Int,
        availableHeightPx: Int
    ): List<ReaderPage>
}

class PaginationEngine(private val context: Context) : Paginator {

    private companion object {
        // A single page rarely exceeds a few thousand characters; 16k is a safe bound.
        const val MEASURE_WINDOW = 16_000
    }

    override suspend fun paginateChapter(
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
        // The caller passes the text field's own bounds: horizontal margins and
        // header/footer chrome are already excluded. Subtracting them again here
        // made pages oversized and forced the reader to scroll for the last lines.
        val usableWidth = availableWidthPx.coerceAtLeast(200)
        val usableHeight = availableHeightPx.coerceAtLeast(200)
        // Compose renders `sp` with the system font scale; TextPaint takes raw px.
        // Without fontScale the engine thinks text is smaller than it renders.
        val fontScale = context.resources.configuration.fontScale
        val fontSizePx = settings.fontSizeSp * density * fontScale
        // Compose applies an explicit lineHeight of fontSize × multiplier. Pin
        // StaticLayout to that same height instead of its font-derived default so
        // the measured line count matches what the reader actually displays.
        val lineHeightPx = fontSizePx * settings.lineHeightMultiplier

        val textPaint = TextPaint().apply {
            textSize = fontSizePx
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
        val pagesStart = mutableListOf<Int>()

        var startOffset = 0
        val length = text.length

        while (startOffset < length) {
            // Stop immediately when the caller cancels the job (e.g. rapid page
            // turns / chapter jumps). Without this, cancelled paginations keep
            // allocating StaticLayouts and can OOM the process.
            currentCoroutineContext().ensureActive()
            val endOffset = findPageEndOffset(
                text = text,
                startOffset = startOffset,
                usableWidth = usableWidth,
                usableHeight = usableHeight,
                textPaint = textPaint,
                lineHeightPx = lineHeightPx
            )

            var safeStart = startOffset
            var safeEnd = if (endOffset <= startOffset) {
                (startOffset + 100).coerceAtLeast(startOffset + 1).coerceAtMost(length)
            } else {
                endOffset
            }

            // Never split a multi-code-point character across pages: doing so
            // turns parts of certain words/emojis into replacement glyphs.
            // 1) Page must not START in the middle of a surrogate pair.
            if (safeStart in 1 until length && Character.isLowSurrogate(text[safeStart])) {
                safeStart--
            }
            // 2) Page must not END in the middle of a surrogate pair.
            if (safeEnd in 1 until length && Character.isHighSurrogate(text[safeEnd - 1])) {
                safeEnd++
            }
            // 3) Keep combining marks / variation selectors with their base character.
            val nonSpacing = Character.NON_SPACING_MARK.toInt()
            while (safeEnd < length && Character.getType(text[safeEnd]) == nonSpacing) {
                safeEnd++
            }
            safeStart = safeStart.coerceIn(0, length)
            safeEnd = safeEnd.coerceIn(safeStart, length)

            pagesText.add(text.substring(safeStart, safeEnd).trim())
            // Page text is trimmed, so the chapter offset of the visible text
            // starts past the trimmed leading whitespace.
            val raw = text.substring(safeStart, safeEnd)
            pagesStart.add(safeStart + (raw.length - raw.trimStart().length))
            startOffset = safeEnd
        }

        val totalPages = pagesText.size.coerceAtLeast(1)
        pagesText.mapIndexed { index, pageText ->
            ReaderPage(
                chapterIndex = chapter.index,
                pageIndex = index,
                totalPagesInChapter = totalPages,
                text = pageText,
                chapterTitle = chapter.title,
                startOffsetInChapter = pagesStart.getOrElse(index) { 0 }
            )
        }
    }

    private fun findPageEndOffset(
        text: String,
        startOffset: Int,
        usableWidth: Int,
        usableHeight: Int,
        textPaint: TextPaint,
        lineHeightPx: Float
    ): Int {
        // Only measure a bounded window ahead of startOffset instead of copying the
        // entire remaining chapter (avoids O(N^2) substring allocations).
        val windowEnd = minOf(text.length, startOffset + MEASURE_WINDOW)
        val window = text.substring(startOffset, windowEnd)
        if (window.isEmpty()) return startOffset

        val staticLayout = createStaticLayout(
            text = window,
            paint = textPaint,
            width = usableWidth,
            lineHeightPx = lineHeightPx
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
        lineHeightPx: Float
    ): StaticLayout {
        val naturalHeight = paint.fontMetrics.let { it.descent - it.ascent }
        val spacingAdd = (lineHeightPx - naturalHeight).coerceAtLeast(0f)
        // minSdk is 24, so the builder API (23+) is always available — the old
        // `SDK_INT >= M` branch and its deprecated constructor were dead code.
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(spacingAdd, 1f)
            .setIncludePad(false)
            .build()
    }
}
