package com.lexiread.core.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class UserErrorMessagesTest {

    private val fallback = "This book could not be opened for reading."

    @Test
    fun `intentional illegal state messages reach the user`() {
        val error = IllegalStateException("No public EPUB or text file is available for 'Moby Dick'.")

        assertEquals(
            "No public EPUB or text file is available for 'Moby Dick'.",
            UserErrorMessages.messageFor(error, fallback)
        )
    }

    @Test
    fun `intentional unsupported operation messages reach the user`() {
        val error = UnsupportedOperationException("No EPUB available for Moby Dick")

        assertEquals(
            "No EPUB available for Moby Dick",
            UserErrorMessages.messageFor(error, fallback)
        )
    }

    @Test
    fun `a blank intentional message falls back`() {
        assertEquals(fallback, UserErrorMessages.messageFor(IllegalStateException(""), fallback))
        assertEquals(fallback, UserErrorMessages.messageFor(IllegalStateException(null as String?), fallback))
    }

    @Test
    fun `technical exception texts are replaced by the fallback`() {
        // Each of these leaked verbatim into the UI before the mapper existed.
        assertEquals(
            fallback,
            UserErrorMessages.messageFor(
                RuntimeException("HTTP 503 Service Unavailable"),
                fallback
            )
        )
        assertEquals(
            fallback,
            UserErrorMessages.messageFor(NumberFormatException("For input string: \"abc\""), fallback)
        )
        assertEquals(
            fallback,
            UserErrorMessages.messageFor(
                IOException("Failed to connect to gutendex.com/172.65.244.124:443"),
                fallback
            )
        )
        assertEquals(
            fallback,
            UserErrorMessages.messageFor(
                SecurityException("Download rejected: host 'example.com' is not in the allowed list."),
                fallback
            )
        )
    }

    @Test
    fun `cancellation never leaks its message`() {
        assertEquals(fallback, UserErrorMessages.messageFor(CancellationException("StandaloneCoroutine was cancelled"), fallback))
    }
}
