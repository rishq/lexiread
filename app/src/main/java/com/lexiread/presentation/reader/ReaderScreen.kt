package com.lexiread.presentation.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lexiread.domain.model.ReaderThemeOption

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.readerSettings

    // Reader colors based on active theme
    val (backgroundColor, textColor) = when (settings.theme) {
        ReaderThemeOption.LIGHT -> Pair(Color(0xFFFFFFFF), Color(0xFF1A1A1A))
        ReaderThemeOption.DARK -> Pair(Color(0xFF121212), Color(0xFFE2E8F0))
        ReaderThemeOption.SEPIA -> Pair(Color(0xFFFBF0D9), Color(0xFF3D2C1E))
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .testTag("reader_screen")
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { constraints.maxWidth }
        val heightPx = with(density) { constraints.maxHeight }

        LaunchedEffect(widthPx, heightPx) {
            viewModel.onContainerDimensionsChanged(widthPx, heightPx)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var totalDragX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDragX = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            totalDragX += dragAmount
                        },
                        onDragEnd = {
                            if (totalDragX < -50f) {
                                viewModel.nextPage()
                            } else if (totalDragX > 50f) {
                                viewModel.previousPage()
                            }
                        }
                    )
                }
        ) {
            if (uiState.isLoadingBook || uiState.isPaginating) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = textColor)
                }
            } else if (uiState.errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = uiState.errorMessage ?: "Failed to load book.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            } else {
                // Tap zones must be BEHIND the text content, otherwise they
                // intercept every tap and word selection never opens.
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(0.3f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { viewModel.previousPage() }
                    )
                    Box(
                        modifier = Modifier
                            .weight(0.4f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { viewModel.toggleControlsOverlay() }
                    )
                    Box(
                        modifier = Modifier
                            .weight(0.3f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { viewModel.nextPage() }
                    )
                }

                val currentPage = uiState.pagesForCurrentChapter.getOrNull(uiState.currentPageIndex)
                
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = settings.marginDp.dp, vertical = 12.dp)
                ) {
                    // Header Chapter Title
                    Text(
                        text = currentPage?.chapterTitle ?: "Chapter ${uiState.currentChapterIndex + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = textColor.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 28.dp, bottom = 8.dp)
                    )

                    // Page Text Canvas — a single Text node keeps composition cheap;
                    // tapped word is resolved from TextLayoutResult offsets.
                    val pageText = currentPage?.text ?: "No page content."
                    var layoutResult by remember(pageText) {
                        mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null)
                    }

                    val textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = settings.fontSizeSp.sp,
                        lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                        fontFamily = when (settings.fontFamilyName.lowercase()) {
                            "serif" -> FontFamily.Serif
                            "sans-serif", "sans" -> FontFamily.SansSerif
                            "monospace", "mono" -> FontFamily.Monospace
                            else -> FontFamily.Serif
                        },
                        color = textColor
                    )

                    Text(
                        text = pageText,
                        style = textStyle,
                        onTextLayout = { layoutResult = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pointerInput(pageText) {
                                detectTapGestures { position ->
                                    val layout = layoutResult ?: return@detectTapGestures
                                    val offset = layout.getOffsetForPosition(position)
                                    if (offset !in pageText.indices) return@detectTapGestures

                                    var start = offset
                                    var end = offset
                                    while (start > 0 && !pageText[start - 1].isWhitespace()) start--
                                    while (end < pageText.length && !pageText[end].isWhitespace()) end++
                                    val cleanWord = pageText.substring(start, end)
                                        .trim { !it.isLetterOrDigit() }

                                    if (cleanWord.isNotEmpty()) {
                                        val paragraphStart = pageText.lastIndexOf("\n\n", offset)
                                            .let { if (it == -1) 0 else it + 2 }
                                        val paragraphEnd = pageText.indexOf("\n\n", offset)
                                            .let { if (it == -1) pageText.length else it }
                                        viewModel.onWordSelected(
                                            cleanWord,
                                            pageText.substring(paragraphStart, paragraphEnd)
                                        )
                                    }
                                }
                            }
                    )

                    // Footer Page Counter
                    val totalPages = uiState.pagesForCurrentChapter.size.coerceAtLeast(1)
                    val currentPageNum = uiState.currentPageIndex + 1
                    Text(
                        text = "$currentPageNum / $totalPages",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )
                }

                // Interactive Tap Zones removed from here: they were drawn above the
                // text and intercepted all word taps (translation sheet never opened).
            }

                // Top Bar Controls Overlay
            AnimatedVisibility(
                visible = uiState.showControlsOverlay,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = uiState.book?.title ?: "Reading",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.toggleTocDialog(true) }) {
                            Icon(imageVector = Icons.Filled.FormatListNumbered, contentDescription = "Table of Contents")
                        }
                        IconButton(onClick = { viewModel.toggleBookmarksDialog(true) }) {
                            Icon(imageVector = Icons.Filled.BookmarkBorder, contentDescription = "Bookmarks")
                        }
                        IconButton(onClick = { viewModel.toggleSettingsDialog(true) }) {
                            Icon(imageVector = Icons.Filled.Settings, contentDescription = "Reader Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    )
                )
            }

            // Bottom Navigation & Progress Controls Overlay
            AnimatedVisibility(
                visible = uiState.showControlsOverlay,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        val totalPages = uiState.pagesForCurrentChapter.size.coerceAtLeast(1)
                        val sliderPosition = (uiState.currentPageIndex.toFloat() / (totalPages - 1).coerceAtLeast(1)).coerceIn(0f, 1f)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Chapter ${uiState.currentChapterIndex + 1} / ${uiState.chapters.size.coerceAtLeast(1)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = "Page ${uiState.currentPageIndex + 1} of $totalPages",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Slider(
                            value = sliderPosition,
                            onValueChange = { percent ->
                                val targetPage = (percent * (totalPages - 1)).toInt()
                                viewModel.goToPage(targetPage, saveProgress = false)
                            },
                            onValueChangeFinished = { viewModel.commitProgress() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Word Context Bottom Sheet
            uiState.selectedWordState?.let { selected ->
                WordContextBottomSheet(
                    selectedState = selected,
                    onSpeak = { text -> viewModel.speakWord(text) },
                    onAiExplainClick = { viewModel.requestAiExplanation() },
                    onSaveWordClick = { viewModel.saveWordToVocabulary() },
                    onDismiss = { viewModel.dismissWordSelection() }
                )
            }

            // AI Explanation Dialog
            if (uiState.showAiExplanationDialog) {
                uiState.selectedWordState?.let { selected ->
                    AiExplanationDialog(
                        selectedState = selected,
                        onSpeak = { text -> viewModel.speakWord(text) },
                        onClose = { viewModel.toggleAiExplanationDialog(false) }
                    )
                }
            }

            // Table of Contents Sheet
            if (uiState.showTocDialog) {
                TableOfContentsSheet(
                    chapters = uiState.chapters,
                    currentChapterIndex = uiState.currentChapterIndex,
                    onChapterSelected = { index -> viewModel.goToChapter(index) },
                    onDismiss = { viewModel.toggleTocDialog(false) }
                )
            }

            // Reader Settings Sheet
            if (uiState.showSettingsDialog) {
                ReaderSettingsSheet(
                    settings = uiState.readerSettings,
                    onUpdateTheme = { theme -> viewModel.updateTheme(theme) },
                    onUpdateFontSize = { size -> viewModel.updateFontSize(size) },
                    onUpdateFontFamily = { family -> viewModel.updateFontFamily(family) },
                    onUpdateMarginDp = { margin -> viewModel.updateMarginDp(margin) },
                    onUpdateLineHeight = { lineHeight -> viewModel.updateLineHeight(lineHeight) },
                    onUpdateVolumeKeysPageTurn = { enabled -> viewModel.updateVolumeKeysPageTurn(enabled) },
                    onDismiss = { viewModel.toggleSettingsDialog(false) }
                )
            }

            // Bookmarks Sheet
            if (uiState.showBookmarksDialog) {
                BookmarksSheet(
                    bookmarks = uiState.bookmarks,
                    onBookmarkSelected = { offset -> viewModel.goToPage(offset) },
                    onDeleteBookmark = { id -> viewModel.deleteBookmark(id) },
                    onAddBookmark = { viewModel.addBookmarkSnippet("Chapter ${uiState.currentChapterIndex + 1}, Page ${uiState.currentPageIndex + 1}") },
                    onDismiss = { viewModel.toggleBookmarksDialog(false) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TableOfContentsSheet(
    chapters: List<com.lexiread.domain.model.BookChapter>,
    currentChapterIndex: Int,
    onChapterSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Table of Contents",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
            ) {
                itemsIndexed(chapters) { index, chapter ->
                    val isCurrent = index == currentChapterIndex
                    Card(
                        onClick = { onChapterSelected(index) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )

                            if (isCurrent) {
                                Text(
                                    text = "Reading",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    settings: com.lexiread.domain.model.ReaderSettings,
    onUpdateTheme: (ReaderThemeOption) -> Unit,
    onUpdateFontSize: (Float) -> Unit,
    onUpdateFontFamily: (String) -> Unit,
    onUpdateMarginDp: (Int) -> Unit,
    onUpdateLineHeight: (Float) -> Unit,
    onUpdateVolumeKeysPageTurn: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Reader Settings",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Theme Options
            Text(text = "Theme", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(
                    Triple(ReaderThemeOption.SEPIA, "Sepia", Color(0xFFFBF0D9)),
                    Triple(ReaderThemeOption.LIGHT, "Light", Color(0xFFFFFFFF)),
                    Triple(ReaderThemeOption.DARK, "Dark", Color(0xFF121212))
                ).forEach { (theme, label, bg) ->
                    val selected = settings.theme == theme
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(bg)
                            .clickable { onUpdateTheme(theme) }
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (theme == ReaderThemeOption.DARK) Color.White else Color.Black,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Font Size Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Font Size", style = MaterialTheme.typography.labelLarge)
                Text(text = "${settings.fontSizeSp.toInt()} sp", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = settings.fontSizeSp,
                onValueChange = onUpdateFontSize,
                valueRange = 14f..30f,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Font Family Selector
            Text(text = "Font Family", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Serif", "Sans", "Mono").forEach { font ->
                    val selected = settings.fontFamilyName.equals(font, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onUpdateFontFamily(font) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = font,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = when (font) {
                                    "Serif" -> FontFamily.Serif
                                    "Sans" -> FontFamily.SansSerif
                                    else -> FontFamily.Monospace
                                }
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Page Margins Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Page Margins", style = MaterialTheme.typography.labelLarge)
                Text(text = "${settings.marginDp} dp", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = settings.marginDp.toFloat(),
                onValueChange = { onUpdateMarginDp(it.toInt()) },
                valueRange = 12f..40f,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Volume Keys Page Turn Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Volume Keys Page Turn", style = MaterialTheme.typography.labelLarge)
                    Text(text = "Use Volume Up / Down to turn pages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.volumeKeysPageTurn,
                    onCheckedChange = onUpdateVolumeKeysPageTurn
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(
    bookmarks: List<com.lexiread.domain.model.Bookmark>,
    onBookmarkSelected: (Int) -> Unit,
    onDeleteBookmark: (Int) -> Unit,
    onAddBookmark: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Bookmarks",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                IconButton(onClick = onAddBookmark) {
                    Icon(imageVector = Icons.Filled.Bookmark, contentDescription = "Add Bookmark", tint = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (bookmarks.isEmpty()) {
                Text(
                    text = "No bookmarks added yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                ) {
                    itemsIndexed(bookmarks) { _, bookmark ->
                        Card(
                            onClick = { onBookmarkSelected(bookmark.scrollOffset) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = bookmark.snippet.ifBlank { "Bookmark" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                IconButton(onClick = { onDeleteBookmark(bookmark.id) }) {
                                    Icon(
                                        imageVector = Icons.Filled.BookmarkBorder,
                                        contentDescription = "Delete Bookmark",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
