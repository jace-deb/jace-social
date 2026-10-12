package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.jacedeb.jacesocial.data.Settings

/** The website's themes (server/app/globals.css and lib/theme.ts): Dark, Midnight, Forest and
 *  Light, with your accent color. Settings -> Appearance changes them for every device. */
object JaceColors {
    var bg by mutableStateOf(Color(0xFF111317)); private set
    var bg2 by mutableStateOf(Color(0xFF16181D)); private set
    var panel by mutableStateOf(Color(0xFF1C1F26)); private set
    var text by mutableStateOf(Color(0xFFE6E8EC)); private set
    var muted by mutableStateOf(Color(0xFF8B919C)); private set
    var green by mutableStateOf(Color(0xFF3DDC84)); private set
    var onGreen by mutableStateOf(Color(0xFF0B1A10)); private set
    val red = Color(0xFFE0605A)
    val yellow = Color(0xFFE0B44A)
    var gray by mutableStateOf(Color(0xFF6B717C)); private set
    var light by mutableStateOf(false); private set

    fun apply(theme: String, accent: Color) {
        light = theme == "light"
        when (theme) {
            "midnight" -> { bg = Color(0xFF000000); bg2 = Color(0xFF07080A); panel = Color(0xFF0E1013); text = Color(0xFFE6E8EC); muted = Color(0xFF8B919C); gray = Color(0xFF6B717C) }
            "forest" -> { bg = Color(0xFF0E1512); bg2 = Color(0xFF121B17); panel = Color(0xFF17221D); text = Color(0xFFE6E8EC); muted = Color(0xFF8EA398); gray = Color(0xFF6B717C) }
            "light" -> { bg = Color(0xFFE9EBEF); bg2 = Color(0xFFF2F3F5); panel = Color(0xFFFFFFFF); text = Color(0xFF1D2127); muted = Color(0xFF5E6672); gray = Color(0xFF8A919B) }
            else -> { bg = Color(0xFF111317); bg2 = Color(0xFF16181D); panel = Color(0xFF1C1F26); text = Color(0xFFE6E8EC); muted = Color(0xFF8B919C); gray = Color(0xFF6B717C) }
        }
        green = accent
        // readable text on the accent (dark on light accents, white on dark ones)
        onGreen = if (accent.red * 299 + accent.green * 587 + accent.blue * 114 > 140f / 255f * 1000) Color(0xFF0C1410) else Color.White
    }
}

val ACCENTS = listOf("#3ddc84", "#4f8fd6", "#b07cf0", "#e0605a", "#e0b44a", "#ef7fb4", "#3cc8d8", "#f08a3c")
val THEMES = listOf("dark" to "Dark", "midnight" to "Midnight", "forest" to "Forest", "light" to "Light", "system" to "Match my phone")

fun hexColor(hex: String?, fallback: Color = Color(0xFF3DDC84)): Color =
    hex?.takeIf { Regex("^#[0-9a-fA-F]{6}$").matches(it) }?.let { Color(0xFF000000 or it.drop(1).toLong(16)) } ?: fallback

fun Color.toHex(): String = "#%02x%02x%02x".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

fun statusColor(status: String): Color = when (status) {
    "online" -> JaceColors.green
    "idle" -> JaceColors.yellow
    "dnd" -> JaceColors.red
    else -> JaceColors.gray
}

@Composable
fun JaceTheme(settings: Settings? = null, content: @Composable () -> Unit) {
    val system = isSystemInDarkTheme()
    val theme = when (val t = settings?.theme ?: "dark") { "system" -> if (system) "dark" else "light"; else -> t }
    JaceColors.apply(theme, hexColor(settings?.accent))
    val c = JaceColors
    val scheme = if (c.light) lightColorScheme(
        primary = c.green, onPrimary = c.onGreen, background = c.bg, onBackground = c.text, surface = c.panel, onSurface = c.text,
        surfaceVariant = c.bg2, onSurfaceVariant = c.muted, error = c.red, surfaceContainer = c.bg2, surfaceContainerHigh = c.panel,
    ) else darkColorScheme(
        primary = c.green, onPrimary = c.onGreen, background = c.bg, onBackground = c.text, surface = c.panel, onSurface = c.text,
        surfaceVariant = c.bg2, onSurfaceVariant = c.muted, error = c.red, surfaceContainer = c.bg2, surfaceContainerHigh = c.panel,
    )
    val density = LocalDensity.current
    val scale = (settings?.font_scale ?: 1.0).coerceIn(0.8, 1.4).toFloat()
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * scale)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
