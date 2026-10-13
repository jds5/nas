package org.rokano.nasremote.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Complete M3 schemes from one green seed for devices without dynamic color (Android 9–11).
// Partial schemes would mix the green primary with the default purple container roles.
private val Light = lightColorScheme(
    primary = Color(0xFF23634A), onPrimary = Color.White, primaryContainer = Color(0xFFB1F1CE), onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6357), onSecondary = Color.White, secondaryContainer = Color(0xFFCFE9D9), onSecondaryContainer = Color(0xFF0A1F15),
    tertiary = Color(0xFF3D6373), onTertiary = Color.White, tertiaryContainer = Color(0xFFC1E8FB), onTertiaryContainer = Color(0xFF001F29),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FBF5), onBackground = Color(0xFF171D1A), surface = Color(0xFFF5FBF5), onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DD), onSurfaceVariant = Color(0xFF404943), outline = Color(0xFF707973), outlineVariant = Color(0xFFBFC9C1),
    inverseSurface = Color(0xFF2C322E), inverseOnSurface = Color(0xFFECF2EC), inversePrimary = Color(0xFF95D5B3),
    surfaceDim = Color(0xFFD5DBD6), surfaceBright = Color(0xFFF5FBF5), surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF5EF), surfaceContainer = Color(0xFFE9EFEA), surfaceContainerHigh = Color(0xFFE4EAE4), surfaceContainerHighest = Color(0xFFDEE4DE),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF95D5B3), onPrimary = Color(0xFF003825), primaryContainer = Color(0xFF005138), onPrimaryContainer = Color(0xFFB1F1CE),
    secondary = Color(0xFFB3CCBE), onSecondary = Color(0xFF1F352A), secondaryContainer = Color(0xFF354B40), onSecondaryContainer = Color(0xFFCFE9D9),
    tertiary = Color(0xFFA5CCDF), onTertiary = Color(0xFF073543), tertiaryContainer = Color(0xFF244C5A), onTertiaryContainer = Color(0xFFC1E8FB),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1512), onBackground = Color(0xFFDEE4DE), surface = Color(0xFF0F1512), onSurface = Color(0xFFDEE4DE),
    surfaceVariant = Color(0xFF404943), onSurfaceVariant = Color(0xFFBFC9C1), outline = Color(0xFF8A938C), outlineVariant = Color(0xFF404943),
    inverseSurface = Color(0xFFDEE4DE), inverseOnSurface = Color(0xFF2C322E), inversePrimary = Color(0xFF1E6A4D),
    surfaceDim = Color(0xFF0F1512), surfaceBright = Color(0xFF343B37), surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF171D1A), surfaceContainer = Color(0xFF1B211E), surfaceContainerHigh = Color(0xFF252B28), surfaceContainerHighest = Color(0xFF303633),
)

@Composable
fun NasTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (dark) Dark else Light
    MaterialTheme(colorScheme = colors, content = content)
}
