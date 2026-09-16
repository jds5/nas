package org.rokano.nasremote.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun NasTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (dark) darkColorScheme(primary = Color(0xFF9ED5BA), secondary = Color(0xFFB9CDBC), surface = Color(0xFF101512))
    else lightColorScheme(primary = Color(0xFF23634A), onPrimary = Color.White, primaryContainer = Color(0xFFCAF0D6), secondaryContainer = Color(0xFFDAE7DC), surface = Color(0xFFF5F8F3))
    MaterialTheme(colorScheme = colors, content = content)
}
