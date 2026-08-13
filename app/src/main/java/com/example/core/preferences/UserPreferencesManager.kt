package com.example.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.ReaderSettings
import com.example.domain.model.ReaderThemeOption
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

        ReaderSettings(
            theme = theme,
            fontSizeSp = fontSize,
            lineHeightMultiplier = lineHeight,
            fontFamilyName = fontFamily
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
}
