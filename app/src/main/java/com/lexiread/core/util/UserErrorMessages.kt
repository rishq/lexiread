package com.lexiread.core.util

import kotlinx.coroutines.CancellationException

/**
 * Single place that decides which exception texts may reach the user.
 *
 * A raw `Throwable.message` is developer diagnostics — "HTTP 503 Service
 * Unavailable", "Expected BEGIN_OBJECT but was STRING", "For input string:
 * \"abc\"" — and every screen used to paste it straight into its error state.
 * The convention in the data layer is that IllegalStateException and
 * UnsupportedOperationException carry a full sentence already written for the
 * reader ("No EPUB available for …"); those pass through untouched. Everything
 * else is replaced by the caller's fallback, which knows the context (search,
 * download, import) and can say something useful about it.
 */
object UserErrorMessages {

    fun messageFor(error: Throwable, fallback: String): String = when (error) {
        is CancellationException -> fallback
        is IllegalStateException, is UnsupportedOperationException ->
            error.message?.takeIf { it.isNotBlank() } ?: fallback
        else -> fallback
    }
}
