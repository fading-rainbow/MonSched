package cn.qingke.schedule.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

private val Light = lightColorScheme(primary = Color(0xFF2F6954), secondary = Color(0xFF657A53),
    background = Color(0xFFF7F8F3), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEAF0E7),
    onBackground = Color(0xFF23382E), onSurface = Color(0xFF23382E), onSurfaceVariant = Color(0xFF6D7A70),
    primaryContainer = Color(0xFFDCEBDD), onPrimaryContainer = Color(0xFF204A39), outline = Color(0xFFBCC8BC))
private val Dark = darkColorScheme(primary = Color(0xFFACD4BC), secondary = Color(0xFFBFCFA7),
    background = Color(0xFF131D17), surface = Color(0xFF1E2A22), surfaceVariant = Color(0xFF2B3C30),
    onBackground = Color(0xFFE0EAE0), onSurface = Color(0xFFE0EAE0), onSurfaceVariant = Color(0xFFAFBCAE),
    primaryContainer = Color(0xFF2E4E3B), onPrimaryContainer = Color(0xFFD1EBD8))
val CourseColors = listOf(0xFFDCEBDD, 0xFFE6E4F6, 0xFFF6E6CE, 0xFFD9EAF4, 0xFFF3DDE5, 0xFFE2E9CB, 0xFFDCE8E7, 0xFFE8E2D7).map { Color(it) }
@Composable fun QingKeTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
@Composable fun courseColor(index: Int): Color {
    val color = CourseColors[Math.floorMod(index, CourseColors.size)]
    return if (MaterialTheme.colorScheme.background == Dark.background) lerp(color, Dark.surface, 0.76f) else color
}
