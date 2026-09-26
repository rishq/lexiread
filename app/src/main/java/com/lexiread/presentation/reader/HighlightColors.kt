package com.lexiread.presentation.reader

import androidx.compose.ui.graphics.Color
import com.lexiread.domain.model.HighlightColorKeys

/**
 * Highlighter palette. Backgrounds are applied translucent (see usage in
 * [ReaderScreen]) so the theme text color stays untouched and readable on
 * light, dark and sepia backgrounds alike.
 */
object HighlightColors {

    val allKeys: List<String> = listOf(
        HighlightColorKeys.YELLOW,
        HighlightColorKeys.GREEN,
        HighlightColorKeys.BLUE,
        HighlightColorKeys.PINK
    )

    fun color(key: String): Color = when (key) {
        HighlightColorKeys.GREEN -> Color(0xFF4ADE80)
        HighlightColorKeys.BLUE -> Color(0xFF38BDF8)
        HighlightColorKeys.PINK -> Color(0xFFF472B6)
        else -> Color(0xFFFACC15)
    }
}
