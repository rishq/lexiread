package com.lexiread.data.source

import com.lexiread.domain.model.SourceKind

/**
 * Which extra hosts a book's cover may be loaded from.
 *
 * Every screen that renders a cover has to tell `CoverUrls.sanitize` which
 * hosts that *particular* source serves images from — the built-in image
 * allow-list deliberately does not contain the scraped catalogues. That check
 * used to be copy-pasted per screen as `if (book.id.startsWith("mylib_"))`,
 * which meant adding a source required finding every copy.
 *
 * Keeping the mapping here means a new scraped source is wired into covers in
 * one place, and a screen cannot quietly disagree with another about what is
 * trusted.
 */
fun catalogCoverHostsFor(bookId: String): Set<String> = when {
    bookId.startsWith("${MyLibBookSource.ID_PREFIX}_") ->
        catalogCoverHostsFor(SourceKind.MY_LIB)

    bookId.startsWith("${AnnasArchiveBookSource.ID_PREFIX}_") ->
        catalogCoverHostsFor(SourceKind.ANNAS_ARCHIVE)

    else -> emptySet()
}

/** The same mapping, for callers that hold a [SourceKind] rather than an id. */
fun catalogCoverHostsFor(source: SourceKind): Set<String> = when (source) {
    SourceKind.MY_LIB -> MyLibConfig.COVER_HOSTS
    SourceKind.ANNAS_ARCHIVE -> AnnasArchiveConfig.COVER_HOSTS
    else -> emptySet()
}
