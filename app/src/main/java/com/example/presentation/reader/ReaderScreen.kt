package com.example.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.remember

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Save reading progress on scroll changes
    LaunchedEffect(listState.firstVisibleItemIndex, listState.layoutInfo.totalItemsCount) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            val progress = listState.firstVisibleItemIndex.toFloat() / listState.layoutInfo.totalItemsCount.toFloat()
            viewModel.saveScrollPosition(listState.firstVisibleItemIndex, listState.layoutInfo.totalItemsCount)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.book?.title ?: "Reading",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Library"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.addBookmark(listState.firstVisibleItemIndex, "Bookmark at section ${listState.firstVisibleItemIndex + 1}") }) {
                        Icon(
                            imageVector = Icons.Filled.BookmarkBorder,
                            contentDescription = "Add Bookmark"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier.testTag("reader_screen")
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.isLoadingBook) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage ?: "Failed to load text",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            } else {
                val bookText = uiState.book?.fullText ?: uiState.book?.description ?: "No text content available."
                val paragraphs = remember(bookText) { 
                    bookText.split("\n\n").filter { it.isNotBlank() }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                ) {
                    itemsIndexed(paragraphs) { _, paragraph ->
                        val words = remember(paragraph) { paragraph.split("\\s+".toRegex()) }
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Start
                        ) {
                            words.forEach { word ->
                                val cleanWord = word.trim { !it.isLetterOrDigit() }
                                Text(
                                    text = "$word ",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = uiState.readerSettings.fontSizeSp.sp,
                                        lineHeight = (uiState.readerSettings.fontSizeSp * uiState.readerSettings.lineHeightMultiplier).sp,
                                        fontFamily = when (uiState.readerSettings.fontFamilyName.lowercase()) {
                                            "serif" -> FontFamily.Serif
                                            "sans-serif", "sans" -> FontFamily.SansSerif
                                            "monospace", "mono" -> FontFamily.Monospace
                                            else -> FontFamily.Serif
                                        }
                                    ),
                                    modifier = Modifier.clickable {
                                        if (cleanWord.isNotEmpty()) {
                                            viewModel.onWordSelected(cleanWord, paragraph)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(60.dp))
                    }
                }

                // Progress indicator at the bottom
                val progress = if (listState.layoutInfo.totalItemsCount > 0) {
                    (listState.firstVisibleItemIndex + 1).toFloat() / listState.layoutInfo.totalItemsCount.toFloat()
                } else 0f

                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                )
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
        }
    }
}
