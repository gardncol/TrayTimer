package com.traytimer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Teal200 = Color(0xFF5EEAD4)
val Teal400 = Color(0xFF2DD4BF)
val Teal500 = Color(0xFF14B8A6)
val Teal700 = Color(0xFF0F766E)
val Teal900 = Color(0xFF042F2E)
val GreenGoal = Color(0xFF4CAF50)
val YellowWarn = Color(0xFFFFC107)
val RedMiss = Color(0xFFEF5350)
val GrayOut = Color(0xFF90A4AE)

/** Timer state colors: blue while wearing ("in"), red while trays are out. */
val TimerInBlue = Color(0xFF1E88E5)
val TimerOutRed = Color(0xFFE53935)

private val LightColors = lightColorScheme(
    primary = Teal700,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    secondary = Teal400,
    surface = Color(0xFFFAFDFC),
    background = Color(0xFFF4FBF9),
    surfaceVariant = Color(0xFFE0F2F1)
)

private val DarkColors = darkColorScheme(
    primary = Teal200,
    onPrimary = Color(0xFF00423B),
    primaryContainer = Teal900,
    secondary = Teal400,
    surface = Color(0xFF10201E),
    background = Color(0xFF0B1615),
    surfaceVariant = Color(0xFF1C2E2B)
)

val AppTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 56.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp)
)

@Composable
fun TrayTimerTheme(themeMode: String, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}