package dev.jacedeb.jacesocial.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The website's dark theme (server/app/globals.css)
object JaceColors {
    val bg = Color(0xFF111317)
    val bg2 = Color(0xFF16181D)
    val panel = Color(0xFF1C1F26)
    val text = Color(0xFFE6E8EC)
    val muted = Color(0xFF8B919C)
    val green = Color(0xFF3DDC84)
    val onGreen = Color(0xFF0B1A10)
    val red = Color(0xFFE0605A)
    val yellow = Color(0xFFE0B44A)
    val gray = Color(0xFF6B717C)
}

fun statusColor(status: String): Color = when (status) {
    "online" -> JaceColors.green
    "idle" -> JaceColors.yellow
    "dnd" -> JaceColors.red
    else -> JaceColors.gray
}

@Composable
fun JaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = JaceColors.green, onPrimary = JaceColors.onGreen,
            background = JaceColors.bg, onBackground = JaceColors.text,
            surface = JaceColors.panel, onSurface = JaceColors.text,
            surfaceVariant = JaceColors.bg2, onSurfaceVariant = JaceColors.muted,
            error = JaceColors.red,
        ),
        content = content,
    )
}
