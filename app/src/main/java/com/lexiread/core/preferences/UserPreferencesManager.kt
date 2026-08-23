package com.lexiread.core.preferences

import android.content.Context
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
        val IS_PAGINATED = androidx.datastore.preferences.core.booleanPreferencesKey("is_paginated")
        val VOLUME_KEYS_PAGE_TURN = androidx.datastore.preferences.core.booleanPreferencesKey("volume_keys_page_turn")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val CLAUDE_API_KEY = stringPreferencesKey("claude_api_key")
        val DEEPSEEK_API_KEY = stringPreferencesKey("deepseek_api_key")
    }

    val geminiApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.GEMINI_API_KEY].orEmpty()
    }

    suspend fun updateGeminiApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.GEMINI_API_KEY] = key.trim()
        }
    }

    val aiProvider: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.AI_PROVIDER] ?: "gemini"
    }

    suspend fun updateAiProvider(provider: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AI_PROVIDER] = provider
        }
    }

    val openAiApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.OPENAI_API_KEY].orEmpty()
    }

    suspend fun updateOpenAiApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OPENAI_API_KEY] = key.trim()
        }
    }

    val claudeApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.CLAUDE_API_KEY].orEmpty()
    }

    suspend fun updateClaudeApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CLAUDE_API_KEY] = key.trim()
        }
    }

    val deepSeekApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEEPSEEK_API_KEY].orEmpty()
    }

    suspend fun updateDeepSeekApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEEPSEEK_API_KEY] = key.trim()
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
        val isPaginated = prefs[Keys.IS_PAGINATED] ?: true
        val volumeKeysPageTurn = prefs[Keys.VOLUME_KEYS_PAGE_TURN] ?: false

        ReaderSettings(
            theme = theme,
            fontSizeSp = fontSize,
            lineHeightMultiplier = lineHeight,
            fontFamilyName = fontFamily,
            marginDp = marginDp,
            isPaginated = isPaginated,
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

    suspend fun updateIsPaginated(isPaginated: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_PAGINATED] = isPaginated
        }
    }

    suspend fun updateVolumeKeysPageTurn(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VOLUME_KEYS_PAGE_TURN] = enabled
        }
    }
}
