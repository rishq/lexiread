package com.lexiread.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lexiread.domain.model.ReaderThemeOption

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val reader = state.readerSettings

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("settings_screen")
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif
            ),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 1. Reader Appearance Theme
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Filled.Palette, contentDescription = "Theme Palette Icon", tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reader Color Theme",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ThemeOptionCard(
                        title = "Sepia",
                        bg = Color(0xFFFBF0D9),
                        fg = Color(0xFF3E2B18),
                        isSelected = reader.theme == ReaderThemeOption.SEPIA,
                        onClick = { viewModel.setTheme(ReaderThemeOption.SEPIA) },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeOptionCard(
                        title = "Light",
                        bg = Color(0xFFFFFFFF),
                        fg = Color(0xFF0F172A),
                        isSelected = reader.theme == ReaderThemeOption.LIGHT,
                        onClick = { viewModel.setTheme(ReaderThemeOption.LIGHT) },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeOptionCard(
                        title = "Dark",
                        bg = Color(0xFF0F172A),
                        fg = Color(0xFFF8FAFC),
                        isSelected = reader.theme == ReaderThemeOption.DARK,
                        onClick = { viewModel.setTheme(ReaderThemeOption.DARK) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Reader Typography Settings
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Filled.FormatSize, contentDescription = "Typography Icon", tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reading Typography",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Font Size: ${reader.fontSizeSp.toInt()} sp",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = reader.fontSizeSp,
                    onValueChange = { viewModel.setFontSize(it) },
                    valueRange = 14f..28f,
                    steps = 6,
                    modifier = Modifier.testTag("font_size_slider")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Line Spacing: ${String.format("%.1f", reader.lineHeightMultiplier)}x",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = reader.lineHeightMultiplier,
                    onValueChange = { viewModel.setLineHeight(it) },
                    valueRange = 1.2f..2.0f,
                    steps = 3
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. AI & Language Translation Settings
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "AI Tutor & Translation",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Filled.Translate, contentDescription = "Language Translation Icon", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Target Language:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.weight(1f))
                    val displayLang = when (state.targetLanguage.lowercase()) {
                        "ru" -> "Russian (RU)"
                        "es" -> "Spanish (ES)"
                        "de" -> "German (DE)"
                        "fr" -> "French (FR)"
                        else -> state.targetLanguage.uppercase()
                    }
                    Text(
                        text = displayLang,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // AI Provider Selector
                Text(text = "AI Provider", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                val providers = listOf(
                    com.lexiread.data.repository.AiProviders.GEMINI to "Gemini",
                    com.lexiread.data.repository.AiProviders.CHATGPT to "ChatGPT",
                    com.lexiread.data.repository.AiProviders.CLAUDE to "Claude",
                    com.lexiread.data.repository.AiProviders.DEEPSEEK to "DeepSeek"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    providers.forEach { (providerId, label) ->
                        val selected = state.aiProvider == providerId
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { viewModel.setAiProvider(providerId) }
                                .padding(vertical = 1.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // API Key for the selected provider (stored locally on device via DataStore)
                val keyLabel = when (state.aiProvider) {
                    com.lexiread.data.repository.AiProviders.CHATGPT -> "OpenAI API Key"
                    com.lexiread.data.repository.AiProviders.CLAUDE -> "Anthropic API Key"
                    com.lexiread.data.repository.AiProviders.DEEPSEEK -> "DeepSeek API Key"
                    else -> "Gemini API Key"
                }
                Text(text = keyLabel, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(6.dp))
                val selectedProviderKey = when (state.aiProvider) {
                    com.lexiread.data.repository.AiProviders.CHATGPT -> state.openAiApiKey
                    com.lexiread.data.repository.AiProviders.CLAUDE -> state.claudeApiKey
                    com.lexiread.data.repository.AiProviders.DEEPSEEK -> state.deepSeekApiKey
                    else -> state.geminiApiKey
                }
                var keyValue by remember(state.aiProvider) { mutableStateOf(selectedProviderKey) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = keyValue,
                        onValueChange = { keyValue = it },
                        placeholder = { Text("Paste your API key") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.material3.Button(onClick = {
                        when (state.aiProvider) {
                            com.lexiread.data.repository.AiProviders.CHATGPT -> viewModel.setOpenAiApiKey(keyValue)
                            com.lexiread.data.repository.AiProviders.CLAUDE -> viewModel.setClaudeApiKey(keyValue)
                            com.lexiread.data.repository.AiProviders.DEEPSEEK -> viewModel.setDeepSeekApiKey(keyValue)
                            else -> viewModel.setGeminiApiKey(keyValue)
                        }
                    }) {
                        Text("Save")
                    }
                }
                if (selectedProviderKey.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Key saved on this device",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF10B981)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Your API key is stored only locally on your device. It is never sent anywhere except the official API of the selected provider.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${com.lexiread.data.repository.AiProviders.displayName(state.aiProvider)} AI Engine:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    val (statusText, statusColor) = if (selectedProviderKey.isNotBlank()) {
                        "Active" to Color(0xFF10B981)
                    } else {
                        "Not configured — enter API key above" to MaterialTheme.colorScheme.secondary
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = statusColor
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // App Information Footer
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "LexiRead v1.0 • AI-Powered English Reader",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Read • Select • Understand • Learn",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            Text(
                text = "GitHub: rishq/lexiread",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    uriHandler.openUri("https://github.com/rishq/lexiread")
                }
            )
        }
    }
}

@Composable
private fun ThemeOptionCard(
    title: String,
    bg: Color,
    fg: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(70.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = fg
                )
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
