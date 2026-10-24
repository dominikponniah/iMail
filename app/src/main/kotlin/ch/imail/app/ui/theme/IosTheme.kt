package ch.imail.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** iOS system colors (light/dark) as used by Apple Mail. */
data class IosColors(
    val blue: Color,
    val background: Color,         // systemGroupedBackground
    val cell: Color,               // secondarySystemGroupedBackground
    val separator: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val fill: Color,               // tertiarySystemFill
    val red: Color,
    val orange: Color,
    val green: Color,
    val dark: Boolean,
)

private val light = IosColors(
    blue = Color(0xFF007AFF), background = Color(0xFFF2F2F7), cell = Color(0xFFFFFFFF), separator = Color(0xFFC6C6C8),
    label = Color(0xFF000000), secondaryLabel = Color(0x993C3C43), tertiaryLabel = Color(0x4D3C3C43), fill = Color(0x1F767680),
    red = Color(0xFFFF3B30), orange = Color(0xFFFF9500), green = Color(0xFF34C759), dark = false,
)
private val dark = IosColors(
    blue = Color(0xFF0A84FF), background = Color(0xFF000000), cell = Color(0xFF1C1C1E), separator = Color(0xFF38383A),
    label = Color(0xFFFFFFFF), secondaryLabel = Color(0x99EBEBF5), tertiaryLabel = Color(0x4DEBEBF5), fill = Color(0x3D767680),
    red = Color(0xFFFF453A), orange = Color(0xFFFF9F0A), green = Color(0xFF30D158), dark = true,
)

/** iOS text styles (sizes from the Human Interface Guidelines at the default Dynamic Type setting). */
object IosType {
    private val face = FontFamily.SansSerif
    val largeTitle = TextStyle(fontFamily = face, fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold)
    val title3 = TextStyle(fontFamily = face, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontFamily = face, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = face, fontSize = 17.sp, lineHeight = 22.sp)
    val subheadline = TextStyle(fontFamily = face, fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontFamily = face, fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontFamily = face, fontSize = 12.sp, lineHeight = 16.sp)
}

val LocalIos = staticCompositionLocalOf { light }

@Composable
fun IosTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) dark else light
    val scheme = if (c.dark) darkColorScheme(primary = c.blue, background = c.background, surface = c.cell, onSurface = c.label, onBackground = c.label)
    else lightColorScheme(primary = c.blue, background = c.background, surface = c.cell, onSurface = c.label, onBackground = c.label)
    CompositionLocalProvider(LocalIos provides c) { MaterialTheme(colorScheme = scheme, content = content) }
}
