package com.lexiread.core.util

/** Shared prompt hygiene + cache-key hashing for AI-backed repositories. */
object PromptSanitizer {
    fun sanitizePromptInput(input: String, maxLen: Int): String {
        return input.trim().take(maxLen).replace("\n", " ").replace("\r", " ")
    }

    /** Escapes only for embedding inside prompt tags. */
    fun escapeForPrompt(input: String): String {
        return input.replace("<", "&lt;").replace(">", "&gt;")
    }

    fun sha256(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
