package com.blackcore.callstar.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 테마 모드: 시스템 따라가기 / 라이트 고정 / 다크 고정 */
enum class ThemeMode(val label: String, val key: String) {
    SYSTEM("시스템", "system"),
    LIGHT("라이트", "light"),
    DARK("다크", "dark");

    companion object {
        fun fromKey(k: String?): ThemeMode = entries.firstOrNull { it.key == k } ?: SYSTEM
    }
}

// ---- 브랜드 팔레트: 틸/그린(통화) + 골드(별점 포인트) ----

// 공통 포인트(별점) — 라이트/다크 공용
val StarGold = Color(0xFFF5B301)
val LaterGray = Color(0xFF78909C)
val DeleteRed = Color(0xFFD84343)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00897B),            // teal 600
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2DFDB),   // teal 100
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4C6360),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F9F8),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    // 카드/컨테이너 계열을 틸-중립 톤으로 명시(미정의 시 새어나오는 기본 보라 제거)
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F6F5),
    surfaceContainer = Color(0xFFECF2F0),
    surfaceContainerHigh = Color(0xFFE6EDEB),
    surfaceContainerHighest = Color(0xFFE0E7E5),
    error = DeleteRed,
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DB6AC),            // teal 300
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFB0CCC7),
    onSecondary = Color(0xFF1C3531),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF171D1B),
    onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF889390),
    surfaceContainerLowest = Color(0xFF0B100F),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B2220),
    surfaceContainerHigh = Color(0xFF262E2C),
    surfaceContainerHighest = Color(0xFF313937),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0A0A),
)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun CallStarTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDarkTheme(mode)) DarkColors else LightColors,
        content = content,
    )
}
