package com.lexiread.core.util

/**
 * Filters cover URLs through the image allow-list before they reach the image
 * loader.
 *
 * Cover URLs come from remote catalogue responses and from scraped markup, so a
 * scraped page can point the loader anywhere. [UrlValidator.requireTrustedImageUrl]
 * existed for exactly this but had no production caller — it was only exercised by
 * a test, which made the allow-list look enforced when nothing checked it.
 */
object CoverUrls {

    /**
     * Returns [url] when it is a fetchable HTTPS image on a trusted host, or
     * `null` so the caller can fall back to a placeholder. Never throws: a bad
     * cover must degrade to a missing image, not crash a screen.
     */
    fun sanitize(url: String?): String? {
        val raw = url?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { UrlValidator.requireTrustedImageUrl(raw) }.getOrNull()
    }
}
