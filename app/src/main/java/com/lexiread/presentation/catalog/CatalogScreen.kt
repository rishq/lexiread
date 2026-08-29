package com.lexiread.presentation.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lexiread.domain.model.CatalogBook
import com.lexiread.domain.model.SourceKind
import kotlinx.coroutines.launch

@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    onBookClick: (String) -> Unit,
    onReadClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CatalogEffect.OpenReader -> onReadClick(effect.bookId)
                is CatalogEffect.OpenDetails -> onBookClick(effect.bookId)
                is CatalogEffect.ShowMessage -> scope.launch { snackbarHostState.showSnackbar(effect.message) }
            }
        }
    }

    // Trigger the next page a few rows early so scrolling never visibly stalls.
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= state.books.size - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(shouldLoadMore, state.books.size) {
        if (shouldLoadMore) viewModel.loadNextPage()
    }

    Box(modifier = modifier.fillMaxSize().testTag("catalog_screen")) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Discover Books",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                ),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.onQueryChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .testTag("catalog_search_field"),
                placeholder = { Text("Search title, author, or genre...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear")
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            CategoryRow(
                categories = viewModel.categories,
                activeCategory = state.activeCategory,
                onSelect = { viewModel.selectCategory(it) }
            )

            SourceFilterRow(
                sources = state.sources,
                onToggle = { viewModel.toggleSource(it) }
            )

            when {
                state.isLoading -> CenteredMessage { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                state.errorMessage != null && state.books.isEmpty() -> CenteredMessage {
                    ErrorBlock(message = state.errorMessage.orEmpty(), onRetry = { viewModel.retry() })
                }
                state.books.isEmpty() -> CenteredMessage {
                    Text(
                        text = if (state.query.isBlank()) "No books available right now."
                        else "No books found for '${state.query}'.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> CatalogList(
                    books = state.books,
                    listState = listState,
                    openingBookId = state.openingBookId,
                    isLoadingMore = state.isLoadingMore,
                    hasMore = state.hasMore,
                    onCardClick = { viewModel.openDetails(it) },
                    onReadClick = { viewModel.openBook(it) },
                    onAddLibrary = { viewModel.addToLibrary(it) }
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface
            )
        }
    }
}

@Composable
private fun CategoryRow(
    categories: List<com.lexiread.domain.model.Category>,
    activeCategory: com.lexiread.domain.model.Category?,
    onSelect: (com.lexiread.domain.model.Category?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = activeCategory == null,
            onClick = { onSelect(null) },
            label = { Text("Popular") }
        )
        categories.forEach { category ->
            FilterChip(
                selected = activeCategory?.id == category.id,
                onClick = { onSelect(category) },
                label = { Text(category.title) }
            )
        }
    }
}

@Composable
private fun SourceFilterRow(
    sources: Set<SourceKind>,
    onToggle: (SourceKind) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SourceKind.entries.forEach { kind ->
            FilterChip(
                selected = kind in sources,
                onClick = { onToggle(kind) },
                label = { Text(sourceLabel(kind)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            )
        }
    }
}

@Composable
private fun CatalogList(
    books: List<CatalogBook>,
    listState: LazyListState,
    openingBookId: String?,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onCardClick: (CatalogBook) -> Unit,
    onReadClick: (CatalogBook) -> Unit,
    onAddLibrary: (CatalogBook) -> Unit
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(books, key = { it.id }) { book ->
            CatalogBookCard(
                book = book,
                isOpening = book.id == openingBookId,
                onCardClick = { onCardClick(book) },
                onReadClick = { onReadClick(book) },
                onAddLibrary = { onAddLibrary(book) }
            )
        }
        if (isLoadingMore || hasMore) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoadingMore) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun CatalogBookCard(
    book: CatalogBook,
    isOpening: Boolean,
    onCardClick: () -> Unit,
    onReadClick: () -> Unit,
    onAddLibrary: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardClick() }
            .testTag("catalog_book_card_${book.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverImage(url = book.coverUrl, title = book.title)

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = book.authorLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(kind = book.source)
                    Spacer(modifier = Modifier.width(6.dp))
                    if (book.canRead) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("Readable", fontSize = 10.sp) },
                            modifier = Modifier.height(24.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                disabledContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                disabledLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onReadClick,
                        enabled = book.canRead && !isOpening,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isOpening) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Read", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onAddLibrary,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Library", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Covers are frequently missing: Open Library returns no `cover_i` for the
 * majority of older works. Falling back to a titled placeholder keeps the grid
 * visually even instead of collapsing into empty boxes.
 */
@Composable
private fun CoverImage(url: String?, title: String) {
    Box(
        modifier = Modifier
            .width(64.dp)
            .height(90.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = title.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(kind: SourceKind) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(sourceLabel(kind), fontSize = 10.sp) },
        modifier = Modifier.height(24.dp),
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            disabledLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    )
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
        content = { content() }
    )
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Catalogue unavailable",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

private fun sourceLabel(kind: SourceKind): String = when (kind) {
    SourceKind.GUTENDEX -> "Gutenberg"
    SourceKind.OPEN_LIBRARY -> "Open Library"
    SourceKind.GOOGLE_BOOKS -> "Google Books"
}

private const val LOAD_MORE_THRESHOLD = 5
