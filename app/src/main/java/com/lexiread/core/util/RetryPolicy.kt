package com.lexiread.core.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Retries transient network failures with exponential backoff.
 *
 * Only failures that can plausibly succeed on a second attempt are retried.
 * A 404 or a JSON parse error is permanent, so retrying it would just make the
 * user wait longer for the same error. Cancellation is never swallowed.
 */
object RetryPolicy {

    private const val DEFAULT_ATTEMPTS = 3
    private const val DEFAULT_INITIAL_DELAY_MS = 400L
    private const val DEFAULT_MAX_DELAY_MS = 3_000L

    suspend fun <T> retryWithBackoff(
        attempts: Int = DEFAULT_ATTEMPTS,
        initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
        maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
        isRetryable: (Throwable) -> Boolean = ::isTransient,
        block: suspend () -> T
    ): T {
        require(attempts >= 1) { "attempts must be >= 1" }

        var attempt = 0
        while (true) {
            attempt++
            try {
                return block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (attempt >= attempts || !isRetryable(error)) throw error
                // 400ms, 800ms, 1600ms ... capped at maxDelayMs.
                val factor = 1L shl (attempt - 1)
                delay((initialDelayMs * factor).coerceAtMost(maxDelayMs))
            }
        }
    }

    fun isTransient(error: Throwable): Boolean = when (error) {
        is CancellationException -> false
        is SocketTimeoutException -> true
        is HttpException -> error.code() >= 500 || error.code() == 429
        is IOException -> true
        else -> false
    }
}
