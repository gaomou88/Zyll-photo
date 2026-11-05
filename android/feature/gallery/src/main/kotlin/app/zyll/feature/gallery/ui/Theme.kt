package app.zyll.feature.gallery.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Zyll 自己的视觉风格：墨绿 + 琥珀，暖白纸色背景，偏方正的圆角。
 * 刻意不用 Google 相册的蓝色系、悬浮胶囊导航和居中日期标题。
 */
private val Pine = Color(0xFF0F6B5F)
private val PineLight = Color(0xFF7FD3C3)
private val Amber = Color(0xFFC98A2B)
private val AmberLight = Color(0xFFF2C27A)

private val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEDE6),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE3BF),
    onSecondaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF8F6F1),
    onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFF8F6F1),
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFE6E3DA),
    onSurfaceVariant = Color(0xFF4A4840),
    surfaceContainer = Color(0xFFEFECE4),
    surfaceContainerHigh = Color(0xFFE9E5DC),
    outline = Color(0xFF7B786E),
)

private val DarkColors = darkColorScheme(
    primary = PineLight,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0B4F46),
    onPrimaryContainer = Color(0xFFCDEDE6),
    secondary = AmberLight,
    onSecondary = Color(0xFF462A00),
    secondaryContainer = Color(0xFF5F3F0B),
    onSecondaryContainer = Color(0xFFFBE3BF),
    background = Color(0xFF111413),
    onBackground = Color(0xFFE3E2DE),
    surface = Color(0xFF111413),
    onSurface = Color(0xFFE3E2DE),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceContainer = Color(0xFF1B1F1E),
    surfaceContainerHigh = Color(0xFF242928),
    outline = Color(0xFF89938F),
)

private val ZyllTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** 时间线分组标题：左对齐、粗体月份 + 细体日期。 */
val SectionTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)

private val ZyllShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun ZyllTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = ZyllTypography,
        shapes = ZyllShapes,
        content = content,
    )
}
