package com.lexiread.presentation.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser check Anna's Archive answers with.
 *
 * The site sits behind DDoS-Guard, whose page is neither Cloudflare's "Just a
 * moment…" nor the site's own content, so the dialog has to recognise it
 * before it can tell "the check is still running" from "the check passed".
 */
class ChallengeWebViewDialogTest {

    @Test
    fun `recognises the ddos guard challenge page`() {
        assertTrue(isChallengePage("DDoS-Guard", ""))
        // The heading element is the reliable marker: the title is localised.
        assertTrue(isChallengePage("Whatever it is called", "ddg"))
    }

    @Test
    fun `recognises the cloudflare challenge page`() {
        assertTrue(isChallengePage("Just a moment...", ""))
        assertTrue(isChallengePage("Attention Required! | Cloudflare", ""))
    }

    @Test
    fun `a real page is not a challenge`() {
        assertFalse(isChallengePage("Search: dickens - Anna's Archive", ""))
        assertFalse(isChallengePage("", ""))
    }
}
