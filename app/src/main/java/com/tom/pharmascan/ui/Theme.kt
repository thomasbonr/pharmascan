package com.tom.pharmascan.ui

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

/**
 * Palette. Un scanner s'utilise le plus souvent dans un placard mal éclairé,
 * écran près du visage : on part sur une base sombre, qui réduit aussi
 * l'éblouissement réfléchi sur la boîte pendant la visée.
 *
 * Accent : un vert menthe froid, lisible sur fond sombre, distinct du vert
 * "validation" pour éviter la confusion avec les états de statut.
 */
private val Mint = Color(0xFF5BE3B0)
private val MintDark = Color(0xFF00A87A)
private val Ink = Color(0xFF0D1512)
private val Surface1 = Color(0xFF15201C)
private val Surface2 = Color(0xFF1E2C27)

val StatusSuccess = Color(0xFF4ADE80)
val StatusWarning = Color(0xFFFBBF24)
val StatusError = Color(0xFFF87171)
val StatusNeutral = Color(0xFF94A3B8)

private val DarkScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00281C),
    primaryContainer = MintDark,
    onPrimaryContainer = Color(0xFFDFFFF2),
    secondary = Color(0xFF7FD1C4),
    background = Ink,
    onBackground = Color(0xFFE6F2ED),
    surface = Surface1,
    onSurface = Color(0xFFE6F2ED),
    surfaceVariant = Surface2,
    onSurfaceVariant = Color(0xFFAFC4BC),
    error = StatusError,
    outline = Color(0xFF3D544C)
)

private val LightScheme = lightColorScheme(
    primary = MintDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F2DF),
    onPrimaryContainer = Color(0xFF00251A),
    background = Color(0xFFF6FBF8),
    surface = Color.White,
    surfaceVariant = Color(0xFFE3EEE9),
    error = Color(0xFFBA1A1A)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * L'écran de scan force le thème sombre quel que soit le réglage système :
 * une interface claire derrière une preview caméra crée des halos qui gênent
 * la visée. Les autres écrans suivent le système.
 */
@Composable
fun PharmaScanTheme(
    forceDark: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = forceDark || isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
