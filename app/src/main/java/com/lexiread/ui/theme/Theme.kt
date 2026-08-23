package com.lexiread.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.lexiread.domain.model.ReaderThemeOption

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = PrimaryPurple,
    onPrimaryContainer = PrimaryContainerPurple,
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = DarkOnBackground,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryPurple,
    onPrimary = OnPrimaryPurple,
    primaryContainer = PrimaryContainerPurple,
    onPrimaryContainer = OnPrimaryContainerPurple,
    secondary = SecondaryTeal,
    onSecondary = OnSecondaryTeal,
    secondaryContainer = SecondaryContainerTeal,
    onSecondaryContainer = OnSecondaryContainerTeal,
    tertiary = TertiaryWarmAmber,
    onTertiary = OnTertiaryWarmAmber,
    tertiaryContainer = TertiaryContainerAmber,
    onTertiaryContainer = OnTertiaryContainerAmber,
    background = LightBackground,
    surface = LightSurface,
    onBackground = LightOnBackground,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline
)

private val SepiaColorScheme = lightColorScheme(
    primary = SepiaPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = SepiaSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E6CC),
    onSecondaryContainer = SepiaOnSurface,
    background = SepiaBackground,
    surface = SepiaSurface,
    onBackground = SepiaOnBackground,
    onSurface = SepiaOnSurface,
    surfaceVariant = SepiaSurfaceVariant,
    onSurfaceVariant = Color(0xFF6B5845),
    outline = Color(0xFF9E8E75)
)

@Composable
fun LexiReadTheme(
    readerTheme: ReaderThemeOption? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when (readerTheme) {
        ReaderThemeOption.LIGHT -> LightColorScheme
        ReaderThemeOption.DARK -> DarkColorScheme
        ReaderThemeOption.SEPIA -> SepiaColorScheme
        null -> if (darkTheme) DarkColorScheme else LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    LexiReadTheme(darkTheme = darkTheme, content = content)
}

