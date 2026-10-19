package com.fingertip.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.fingertip.app.ui.ThemeMode

// 老板黑（近纯黑，蓝 accent，状态绿 #30D158）
private val BossBlackColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    background = Color(0xFF0A0A0C),
    surface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2C2C2E),
    onPrimary = Color.White,
    onBackground = Color(0xFFF5F5F7),
    onSurface = Color(0xFFF5F5F7),
    onSurfaceVariant = Color(0xFF98989D),
    secondary = Color(0xFF98989D),
    outline = Color(0xFF5A5A5E),
    outlineVariant = Color(0xFF48484C),
    tertiary = Color(0xFF30D158),
)

// 脸色白（苹果白，状态绿 #34C759）
private val FaceWhiteColors = lightColorScheme(
    primary = Color(0xFF007AFF),
    background = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8E8ED),
    onPrimary = Color.White,
    onBackground = Color(0xFF1D1D1F),
    onSurface = Color(0xFF1D1D1F),
    onSurfaceVariant = Color(0xFF6E6E73),
    secondary = Color(0xFF86868B),
    outline = Color(0xFFAEAEB2),
    outlineVariant = Color(0xFFC0C0C5),
    tertiary = Color(0xFF34C759),
)

// 淤青紫（深紫黑背景，亮紫 accent，状态绿 #7BD88F）
private val BruisePurpleColors = darkColorScheme(
    primary = Color(0xFFB47EDC),
    background = Color(0xFF241230),
    surface = Color(0xFF37204A),
    surfaceVariant = Color(0xFF463060),
    onPrimary = Color(0xFF1A0B24),
    onBackground = Color(0xFFF3EAFB),
    onSurface = Color(0xFFF3EAFB),
    onSurfaceVariant = Color(0xFFB39AC9),
    secondary = Color(0xFFB39AC9),
    outline = Color(0xFF6E5A8A),
    outlineVariant = Color(0xFF584A72),
    tertiary = Color(0xFF7BD88F),
)

// 全局字体规范：5 档语义化字号，统一行高；includeFontPadding=false 去掉 Android
// 为拉丁字体预留的上下 padding（中文下会导致框内文字偏下、不居中）。
private val AppTypography = Typography(
    // 关于页应用名
    displaySmall = TextStyle(
        fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    ),
    // 页面标题：设置页标题 / 键盘上屏标题 / 连接态设备名
    titleMedium = TextStyle(
        fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    ),
    // 主文字：设置项 / 按钮 / 设备名 / 状态文字
    bodyLarge = TextStyle(
        fontSize = 15.sp, lineHeight = 22.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    ),
    // 正文：滑块数值 / IP:端口 / 空态说明
    bodyMedium = TextStyle(
        fontSize = 13.sp, lineHeight = 19.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    ),
    // 辅助说明：所有原 11sp/12sp 硬编码统一到此档（不再有 11sp）
    bodySmall = TextStyle(
        fontSize = 12.sp, lineHeight = 17.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    ),
)

@Composable
fun FingertipTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val scheme = when (themeMode) {
        ThemeMode.BOSS_BLACK -> BossBlackColors
        ThemeMode.FACE_WHITE -> FaceWhiteColors
        ThemeMode.BRUISE_PURPLE -> BruisePurpleColors
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        content = content,
    )
}
