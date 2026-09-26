package com.lexiread.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lexiread.domain.model.ReaderSettings
import com.lexiread.domain.model.ReaderThemeOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "lexiread_settings")

class UserPreferencesManager(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("reader_theme")
        val FONT_SIZE = floatPreferencesKey("font_size_sp")
        val LINE_HEIGHT = floatPreferencesKey("line_height_multiplier")
        val FONT_FAMILY = stringPreferencesKey("font_family")
        val TARGET_LANG = stringPreferencesKey("target_language")
        val MARGIN_DP = androidx.datastore.preferences.core.intPreferencesKey("margin_dp")
        val VOLUME_KEYS_PAGE_TURN = androidx.datastore.preferences.core.booleanPreferencesKey("volume_keys_page_turn")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val CLAUDE_API_KEY = stringPreferencesKey("claude_api_key")
        val DEEPSEEK_API_KEY = stringPreferencesKey("deepseek_api_key")
        // P1-6: explicit consent for cloud word lookups (dictionary, translation, AI).
        val CLOUD_LOOKUP_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("cloud_lookup_enabled")
        val CLOUD_CONSENT_ASKED = androidx.datastore.preferences.core.booleanPreferencesKey("cloud_consent_asked")
    }

    // P1-5: keys are stored encrypted (AES-GCM, Keystore) and decrypted on read.
    private fun apiKeyFlow(key: Preferences.Key<String>): Flow<String> =
        context.dataStore.data.map { prefs -> ApiKeyCrypto.decrypt(prefs[key].orEmpty()) }

    private suspend fun updateApiKey(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { prefs ->
            prefs[key] = ApiKeyCrypto.encrypt(value.trim())
        }
    }

    val geminiApiKey: Flow<String> = apiKeyFlow(Keys.GEMINI_API_KEY)

    suspend fun updateGeminiApiKey(key: String) = updateApiKey(Keys.GEMINI_API_KEY, key)

    val aiProvider: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.AI_PROVIDER] ?: "gemini"
    }

    suspend fun updateAiProvider(provider: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AI_PROVIDER] = provider
        }
    }

    val openAiApiKey: Flow<String> = apiKeyFlow(Keys.OPENAI_API_KEY)

    suspend fun updateOpenAiApiKey(key: String) = updateApiKey(Keys.OPENAI_API_KEY, key)

    val claudeApiKey: Flow<String> = apiKeyFlow(Keys.CLAUDE_API_KEY)

    suspend fun updateClaudeApiKey(key: String) = updateApiKey(Keys.CLAUDE_API_KEY, key)

    val deepSeekApiKey: Flow<String> = apiKeyFlow(Keys.DEEPSEEK_API_KEY)

    suspend fun updateDeepSeekApiKey(key: String) = updateApiKey(Keys.DEEPSEEK_API_KEY, key)

    /** One-time migration: re-encrypt legacy `plain:` / raw keys, then drop plaintext. */
    suspend fun migrateLegacyKeys() {
        context.dataStore.edit { prefs ->
            listOf(Keys.GEMINI_API_KEY, Keys.OPENAI_API_KEY, Keys.CLAUDE_API_KEY, Keys.DEEPSEEK_API_KEY).forEach { k ->
                val stored = prefs[k].orEmpty()
                if (stored.isNotEmpty() && !ApiKeyCrypto.isEncrypted(stored)) {
                    val plain = if (stored.startsWith("plain:")) stored.removePrefix("plain:") else stored
                    prefs[k] = if (plain.isEmpty()) "" else ApiKeyCrypto.encrypt(plain)
                }
            }
        }
    }

    // P1-6: cloud lookup consent — recipients are disclosed in the consent
    // dialog and Settings (dictionaryapi.dev, mymemory, Gemini/OpenAI/Claude/
    // DeepSeek). Default OFF: no user text leaves the device until opt-in.
    val cloudLookupEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.CLOUD_LOOKUP_ENABLED] ?: false
    }

    val cloudConsentAsked: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.CLOUD_CONSENT_ASKED] ?: false
    }

    suspend fun setCloudLookupEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CLOUD_LOOKUP_ENABLED] = enabled
            prefs[Keys.CLOUD_CONSENT_ASKED] = true
        }
    }

    val readerSettings: Flow<ReaderSettings> = context.dataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME] ?: ReaderThemeOption.SEPIA.name
        val theme = try {
            ReaderThemeOption.valueOf(themeStr)
        } catch (e: Exception) {
            ReaderThemeOption.SEPIA
        }
        val fontSize = prefs[Keys.FONT_SIZE] ?: 18f
        val lineHeight = prefs[Keys.LINE_HEIGHT] ?: 1.4f
        val fontFamily = prefs[Keys.FONT_FAMILY] ?: "Serif"
        val marginDp = prefs[Keys.MARGIN_DP] ?: 20
        val volumeKeysPageTurn = prefs[Keys.VOLUME_KEYS_PAGE_TURN] ?: false

        ReaderSettings(
            theme = theme,
            fontSizeSp = fontSize,
            lineHeightMultiplier = lineHeight,
            fontFamilyName = fontFamily,
            marginDp = marginDp,
            volumeKeysPageTurn = volumeKeysPageTurn
        )
    }

    val targetLanguage: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.TARGET_LANG] ?: "ru"
    }

    suspend fun updateTheme(theme: ReaderThemeOption) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME] = theme.name
        }
    }

    suspend fun updateFontSize(sizeSp: Float) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FONT_SIZE] = sizeSp
        }
    }

    suspend fun updateLineHeight(multiplier: Float) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LINE_HEIGHT] = multiplier
        }
    }

    suspend fun updateFontFamily(family: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FONT_FAMILY] = family
        }
    }

    suspend fun updateTargetLanguage(lang: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TARGET_LANG] = lang
        }
    }

    suspend fun updateMarginDp(marginDp: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MARGIN_DP] = marginDp
        }
    }

    suspend fun updateVolumeKeysPageTurn(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VOLUME_KEYS_PAGE_TURN] = enabled
        }
    }
}
