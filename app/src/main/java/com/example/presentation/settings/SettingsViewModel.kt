package com.example.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.core.preferences.UserPreferencesManager
import com.example.domain.model.ReaderSettings
import com.example.domain.model.ReaderThemeOption
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val readerSettings: ReaderSettings = ReaderSettings(),
    val targetLanguage: String = "ru",
    val isGeminiKeyConfigured: Boolean = false
)

class SettingsViewModel(
    private val preferencesManager: UserPreferencesManager
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesManager.readerSettings,
        preferencesManager.targetLanguage
    ) { reader, targetLang ->
        val apiKey = BuildConfig.GEMINI_API_KEY
        val hasKey = apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY"

        SettingsUiState(
            readerSettings = reader,
            targetLanguage = targetLang,
            isGeminiKeyConfigured = hasKey
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun setTheme(theme: ReaderThemeOption) {
        viewModelScope.launch {
            preferencesManager.updateTheme(theme)
        }
    }

    fun setFontSize(sizeSp: Float) {
        viewModelScope.launch {
            preferencesManager.updateFontSize(sizeSp)
        }
    }

    fun setLineHeight(multiplier: Float) {
        viewModelScope.launch {
            preferencesManager.updateLineHeight(multiplier)
        }
    }

    fun setFontFamily(family: String) {
        viewModelScope.launch {
            preferencesManager.updateFontFamily(family)
        }
    }

    fun setTargetLanguage(lang: String) {
        viewModelScope.launch {
            preferencesManager.updateTargetLanguage(lang)
        }
    }

    class Factory(
        private val preferencesManager: UserPreferencesManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(preferencesManager) as T
        }
    }
}
