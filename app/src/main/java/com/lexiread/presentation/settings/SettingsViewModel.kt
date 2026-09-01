package com.lexiread.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.core.preferences.UserPreferencesManager
import com.lexiread.domain.model.ReaderSettings
import com.lexiread.domain.model.ReaderThemeOption
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val readerSettings: ReaderSettings = ReaderSettings(),
    val targetLanguage: String = "ru",
    val geminiApiKey: String = "",
    val aiProvider: String = com.lexiread.data.repository.AiProviders.GEMINI,
    val openAiApiKey: String = "",
    val claudeApiKey: String = "",
    val deepSeekApiKey: String = ""
)

class SettingsViewModel(
    private val preferencesManager: UserPreferencesManager
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            preferencesManager.readerSettings,
            preferencesManager.targetLanguage,
            preferencesManager.geminiApiKey
        ) { reader, targetLang, geminiKey -> Triple(reader, targetLang, geminiKey) },
        combine(
            preferencesManager.aiProvider,
            preferencesManager.openAiApiKey,
            preferencesManager.claudeApiKey,
            preferencesManager.deepSeekApiKey
        ) { provider, openAiKey, claudeKey, deepSeekKey ->
            listOf(provider, openAiKey, claudeKey, deepSeekKey)
        }
    ) { base, ai ->
        SettingsUiState(
            readerSettings = base.first,
            targetLanguage = base.second,
            geminiApiKey = base.third,
            aiProvider = ai[0],
            openAiApiKey = ai[1],
            claudeApiKey = ai[2],
            deepSeekApiKey = ai[3]
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun setGeminiApiKey(key: String) {
        viewModelScope.launch {
            preferencesManager.updateGeminiApiKey(key)
        }
    }

    fun setAiProvider(provider: String) {
        viewModelScope.launch {
            preferencesManager.updateAiProvider(provider)
        }
    }

    fun setOpenAiApiKey(key: String) {
        viewModelScope.launch {
            preferencesManager.updateOpenAiApiKey(key)
        }
    }

    fun setClaudeApiKey(key: String) {
        viewModelScope.launch {
            preferencesManager.updateClaudeApiKey(key)
        }
    }

    fun setDeepSeekApiKey(key: String) {
        viewModelScope.launch {
            preferencesManager.updateDeepSeekApiKey(key)
        }
    }

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

    fun setVolumeKeysPageTurn(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.updateVolumeKeysPageTurn(enabled)
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
