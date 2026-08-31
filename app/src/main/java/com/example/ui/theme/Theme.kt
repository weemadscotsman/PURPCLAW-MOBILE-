package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ElegantDarkColorScheme = darkColorScheme(
  primary = PurpPrimary,
  onPrimary = Color.White,
  primaryContainer = PurpDeep,
  onPrimaryContainer = TextHighlight,
  secondary = CyanAccent,
  onSecondary = Color(0xFF0F172A),
  secondaryContainer = Color(0xFF1E293B),
  onSecondaryContainer = CyanNeon,
  tertiary = PurpPrimaryDark,
  onTertiary = Color.White,
  background = PurpVoid,
  onBackground = TextPrimary,
  surface = PurpSurface,
  onSurface = TextPrimary,
  surfaceVariant = PurpSurfaceElevated,
  onSurfaceVariant = TextSecondary,
  outline = PurpBorder,
  error = RoseOffline,
  onError = Color.White
)

@Composable
fun MyApplicationTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = ElegantDarkColorScheme,
    typography = Typography,
    content = content
  )
}

