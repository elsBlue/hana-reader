package com.hana.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Paper = Color(0xFFF4EFE6)
val PaperElevated = Color(0xFFFFFAF3)
val Ink = Color(0xFF1F1A16)
val Muted = Color(0xFF8C8278)
val Rose = Color(0xFFB85C5C)
val Subtle = Color(0xFFEBE4D8)

private val colors = lightColorScheme(
    primary = Rose,
    onPrimary = PaperElevated,
    background = Paper,
    onBackground = Ink,
    surface = PaperElevated,
    onSurface = Ink,
    secondary = Ink,
    onSecondary = Paper
)

@Composable
fun HanaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
