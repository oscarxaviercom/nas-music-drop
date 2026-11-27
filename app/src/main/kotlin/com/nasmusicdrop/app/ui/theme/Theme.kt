package com.nasmusicdrop.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    outline = md_theme_light_outline,
    outlineVariant = md_theme_light_outlineVariant
)

private val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    outline = md_theme_dark_outline,
    outlineVariant = md_theme_dark_outlineVariant
)

data class AccentOption(
    val id: String,
    val label: String,
    val swatchColor: Color
)

val AppAccentOptions = listOf(
    AccentOption("TEAL", "Turquesa", Color(0xFF00897B)),
    AccentOption("BLUE", "Azul", Color(0xFF1E88E5)),
    AccentOption("PURPLE", "Morado", Color(0xFF8E24AA)),
    AccentOption("ORANGE", "Naranja", Color(0xFFFB8C00)),
    AccentOption("RED", "Rojo", Color(0xFFE53935)),
    AccentOption("GREEN", "Verde", Color(0xFF43A047)),
    AccentOption("PINK", "Rosa", Color(0xFFD81B60))
)

@Composable
fun NASMusicDropTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentColor: String = "TEAL",
    content: @Composable () -> Unit
) {
    val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val colorScheme = when (accentColor) {
        "DYNAMIC" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            baseScheme
        }
        "BLUE" -> if (darkTheme) baseScheme.copy(
            primary = Color(0xFF82B1FF),
            onPrimary = Color(0xFF002A66),
            primaryContainer = Color(0xFF0D47A1),
            onPrimaryContainer = Color(0xFFD6E4FF),
            secondary = Color(0xFF90CAF9),
            onSecondary = Color(0xFF003258),
            secondaryContainer = Color(0xFF1A3A5C),
            onSecondaryContainer = Color(0xFFD1E4FF),
            tertiary = Color(0xFF80D8FF),
            onTertiary = Color(0xFF003547)
        ) else baseScheme.copy(
            primary = Color(0xFF1565C0),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD6E4FF),
            onPrimaryContainer = Color(0xFF001B3E),
            secondary = Color(0xFF1E88E5),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE3F2FD),
            onSecondaryContainer = Color(0xFF0D47A1),
            tertiary = Color(0xFF0277BD),
            onTertiary = Color.White
        )
        "PURPLE" -> if (darkTheme) baseScheme.copy(
            primary = Color(0xFFCF94FF),
            onPrimary = Color(0xFF3E007A),
            primaryContainer = Color(0xFF5B1B9A),
            onPrimaryContainer = Color(0xFFF3DAFF),
            secondary = Color(0xFFB39DDB),
            onSecondary = Color(0xFF2B1254),
            secondaryContainer = Color(0xFF3D2566),
            onSecondaryContainer = Color(0xFFEBDCFF),
            tertiary = Color(0xFFEA80FC),
            onTertiary = Color(0xFF4A0059)
        ) else baseScheme.copy(
            primary = Color(0xFF7B1FA2),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFF3DAFF),
            onPrimaryContainer = Color(0xFF2C0051),
            secondary = Color(0xFF8E24AA),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFF3E5F5),
            onSecondaryContainer = Color(0xFF4A148C),
            tertiary = Color(0xFF6A1B9A),
            onTertiary = Color.White
        )
        "ORANGE" -> if (darkTheme) baseScheme.copy(
            primary = Color(0xFFFFB74D),
            onPrimary = Color(0xFF4A2500),
            primaryContainer = Color(0xFF8C4600),
            onPrimaryContainer = Color(0xFFFFE0B2),
            secondary = Color(0xFFFFCC80),
            onSecondary = Color(0xFF442600),
            secondaryContainer = Color(0xFF5D3408),
            onSecondaryContainer = Color(0xFFFFE5C2),
            tertiary = Color(0xFFFFAB91),
            onTertiary = Color(0xFF4E1600)
        ) else baseScheme.copy(
            primary = Color(0xFFEF6C00),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFE0B2),
            onPrimaryContainer = Color(0xFF3E1C00),
            secondary = Color(0xFFFB8C00),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFFFF3E0),
            onSecondaryContainer = Color(0xFFE65100),
            tertiary = Color(0xFFD84315),
            onTertiary = Color.White
        )
        "RED" -> if (darkTheme) baseScheme.copy(
            primary = Color(0xFFFF8A80),
            onPrimary = Color(0xFF5C0009),
            primaryContainer = Color(0xFF930016),
            onPrimaryContainer = Color(0xFFFFDAD6),
            secondary = Color(0xFFFFAB91),
            onSecondary = Color(0xFF4E1608),
            secondaryContainer = Color(0xFF632119),
            onSecondaryContainer = Color(0xFFFFDFD6),
            tertiary = Color(0xFFFF80AB),
            onTertiary = Color(0xFF590026)
        ) else baseScheme.copy(
            primary = Color(0xFFC62828),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDAD6),
            onPrimaryContainer = Color(0xFF410002),
            secondary = Color(0xFFE53935),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFFFEBEE),
            onSecondaryContainer = Color(0xFFB71C1C),
            tertiary = Color(0xFFAD1457),
            onTertiary = Color.White
        )
        "GREEN" -> if (darkTheme) baseScheme.copy(
            primary = Color(0xFF81C784),
            onPrimary = Color(0xFF003914),
            primaryContainer = Color(0xFF1B5E20),
            onPrimaryContainer = Color(0xFFC8E6C9),
            secondary = Color(0xFFA5D6A7),
            onSecondary = Color(0xFF0A3815),
            secondaryContainer = Color(0xFF1E4A25),
            onSecondaryContainer = Color(0xFFD7F2D9),
            tertiary = Color(0xFFAED581),
            onTertiary = Color(0xFF1D3700)
        ) else baseScheme.copy(
            primary = Color(0xFF2E7D32),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFC8E6C9),
            onPrimaryContainer = Color(0xFF002107),
            secondary = Color(0xFF43A047),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE8F5E9),
            onSecondaryContainer = Color(0xFF1B5E20),
            tertiary = Color(0xFF558B2F),
            onTertiary = Color.White
        )
        "PINK" -> if (darkTheme) baseScheme.copy(
            primary = Color(0xFFF48FB1),
            onPrimary = Color(0xFF5E002B),
            primaryContainer = Color(0xFF880E4F),
            onPrimaryContainer = Color(0xFFFFD9E3),
            secondary = Color(0xFFF8BBD0),
            onSecondary = Color(0xFF4E0C2A),
            secondaryContainer = Color(0xFF631B3C),
            onSecondaryContainer = Color(0xFFFFE2EC),
            tertiary = Color(0xFFCE93D8),
            onTertiary = Color(0xFF430A57)
        ) else baseScheme.copy(
            primary = Color(0xFFC2185B),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFD9E3),
            onPrimaryContainer = Color(0xFF3E001D),
            secondary = Color(0xFFD81B60),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFFCE4EC),
            onSecondaryContainer = Color(0xFF880E4F),
            tertiary = Color(0xFF8E24AA),
            onTertiary = Color.White
        )
        else -> baseScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun successColor(): Color {
    return if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        GreenSuccess
    } else {
        GreenSuccessLight
    }
}
