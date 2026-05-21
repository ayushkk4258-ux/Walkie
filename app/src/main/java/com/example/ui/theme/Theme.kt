package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = VoxAccent,
    onPrimary = VoxDarkBg,
    primaryContainer = VoxSurface,
    onPrimaryContainer = VoxAccent,
    secondary = VoxSubText,
    onSecondary = VoxDarkBg,
    background = VoxDarkBg,
    onBackground = VoxText,
    surface = VoxSurface,
    onSurface = VoxText,
    outline = VoxBorder
  )

private val LightColorScheme = DarkColorScheme // Force dark theme as requested!


@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Default to true
  // Dynamic color is disabled to preserve custom brand colors
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
