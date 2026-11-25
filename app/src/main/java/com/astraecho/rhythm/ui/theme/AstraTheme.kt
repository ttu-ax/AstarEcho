package com.astraecho.rhythm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AstraColors {
    val VoidBg = Color(0xFF070A16)
    val DeepIndigo = Color(0xFF0F1733)
    val GlassCard = Color(0xB8111B38)
    val GlassBorder = Color(0x5538F2FF)
    val NeonCyan = Color(0xFF38F2FF)
    val ElectricMagenta = Color(0xFFFF3B8D)
    val StarlightGold = Color(0xFFFFD166)
    val CosmicPurple = Color(0xFFB388FF)
    val TextPrimary = Color(0xFFF3F8FF)
    val TextSecondary = Color(0xFFA2B6D9)
}

private val AstraDarkScheme = darkColorScheme(
    primary = AstraColors.NeonCyan,
    onPrimary = Color(0xFF041320),
    secondary = AstraColors.ElectricMagenta,
    onSecondary = Color.White,
    tertiary = AstraColors.StarlightGold,
    background = AstraColors.VoidBg,
    onBackground = AstraColors.TextPrimary,
    surface = AstraColors.DeepIndigo,
    onSurface = AstraColors.TextPrimary
)

@Composable
fun AstraEchoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AstraDarkScheme,
        content = content
    )
}
