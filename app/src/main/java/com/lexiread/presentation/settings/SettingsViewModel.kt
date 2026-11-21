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
    val deepSeekApiKey: String = "",
    // P1-6: offline by default — user text never leaves the device until opt-in.
    val cloudLookupEnabled: Boolean = false,
    /**
     * A hostname the user typed to replace the built-in Anna's Archive host, or
     * empty to use the built-in failover list.
     */
    val annasArchiveHost: String = ""
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
        },
        preferencesManager.cloudLookupEnabled,
        preferencesManager.annasArchiveHost
    ) { base, ai, cloudEnabled, annasHost ->
        SettingsUiState(
            readerSettings = base.first,
            targetLanguage = base.second,
            geminiApiKey = base.third,
            aiProvider = ai[0],
            openAiApiKey = ai[1],
            claudeApiKey = ai[2],
            deepSeekApiKey = ai[3],
            cloudLookupEnabled = cloudEnabled,
            annasArchiveHost = annasHost
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    private val _apiKeySaveError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val apiKeySaveError: StateFlow<String?> = _apiKeySaveError

    private fun saveApiKey(save: suspend () -> Unit) {
        viewModelScope.launch {
            _apiKeySaveError.value = null
            try {
                save()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _apiKeySaveError.value = "Could not securely save the API key. Please try again."
            }
        }
    }

    fun setGeminiApiKey(key: String) {
        saveApiKey { preferencesManager.updateGeminiApiKey(key) }
    }

    fun setAiProvider(provider: String) {
        viewModelScope.launch {
            preferencesManager.updateAiProvider(provider)
        }
    }

    fun setOpenAiApiKey(key: String) {
        saveApiKey { preferencesManager.updateOpenAiApiKey(key) }
    }

    fun setClaudeApiKey(key: String) {
        saveApiKey { preferencesManager.updateClaudeApiKey(key) }
    }

    fun setDeepSeekApiKey(key: String) {
        saveApiKey { preferencesManager.updateDeepSeekApiKey(key) }
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

    fun setVolumeKeysPageTurn(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.updateVolumeKeysPageTurn(enabled)
        }
    }

    /** P1-6: offline switch — no user text leaves the device while off. */
    fun setCloudLookupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setCloudLookupEnabled(enabled)
        }
    }

    /**
     * Anna's Archive's domains are seized and replaced every few months, so the
     * host is user-editable instead of only compiled in. Stored raw; it is
     * reduced to a bare hostname where it is used.
     */
    fun setAnnasArchiveHost(host: String) {
        viewModelScope.launch {
            preferencesManager.updateAnnasArchiveHost(host)
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
