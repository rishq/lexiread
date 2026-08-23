package com.lexiread.core.util

import kotlinx.coroutines.CancellationException

inline fun <T> runSuspendCatching(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

inline fun <T> Result<T>.recoverSuspendCatching(recover: () -> T): Result<T> {
    return if (isSuccess) this else try {
        Result.success(recover())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(exceptionOrNull() ?: e)
    }
}
