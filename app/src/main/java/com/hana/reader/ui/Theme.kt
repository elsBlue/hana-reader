package com.hana.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

val Paper = Color(0xFFF4EFE6)
val PaperElevated = Color(0xFFFFFAF3)
val Ink = Color(0xFF1F1A16)
val Muted = Color(0xFF8C8278)
val Rose = Color(0xFFB85C5C)
val Subtle = Color(0xFFEBE4D8)


/**
 * Legacy warm-ink helper (v1.4.9). v1.5.0 Listen no longer recolors the active
 * sentence — auto-scroll only — so speech sync is not implied. Kept for callers.
 */
fun warmListenInk(night: Boolean): Color {
    val base = if (night) Color(0xFFF3ECE3) else Ink
    return lerp(base, Rose, if (night) 0.12f else 0.15f)
}

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
