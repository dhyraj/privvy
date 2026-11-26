package com.privvy.app.ui.theme

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E4B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEFE1),
    onPrimaryContainer = Color(0xFF063B2E),
    secondary = Color(0xFF4A635B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE8E2),
    onSecondaryContainer = Color(0xFF10251E),
    tertiary = Color(0xFF735D42),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF4E2C8),
    onTertiaryContainer = Color(0xFF2B210F),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF6F5F0),
    onBackground = Color(0xFF17201C),
    surface = Color(0xFFF6F5F0),
    onSurface = Color(0xFF17201C),
    surfaceVariant = Color(0xFFE3E7E1),
    onSurfaceVariant = Color(0xFF47534C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F2EC),
    surfaceContainer = Color(0xFFE9EDE6),
    surfaceContainerHigh = Color(0xFFE1E6DE),
    surfaceContainerHighest = Color(0xFFD9DFD6),
    outline = Color(0xFF738078),
    outlineVariant = Color(0xFFC7D0C8),
    inverseSurface = Color(0xFF2D3832),
    inverseOnSurface = Color(0xFFEDF2EC),
    inversePrimary = Color(0xFFA8E6C8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8E6C8),
    onPrimary = Color(0xFF063B2E),
    primaryContainer = Color(0xFF245844),
    onPrimaryContainer = Color(0xFFCDEFE1),
    secondary = Color(0xFFB6CCC2),
    onSecondary = Color(0xFF1D332A),
    secondaryContainer = Color(0xFF334A40),
    onSecondaryContainer = Color(0xFFDCE8E2),
    tertiary = Color(0xFFE5C79E),
    onTertiary = Color(0xFF3F2E17),
    tertiaryContainer = Color(0xFF594631),
    onTertiaryContainer = Color(0xFFF4E2C8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1511),
    onBackground = Color(0xFFE2E9E3),
    surface = Color(0xFF0E1511),
    onSurface = Color(0xFFE2E9E3),
    surfaceVariant = Color(0xFF3B4941),
    onSurfaceVariant = Color(0xFFBCC8C0),
    surfaceContainerLowest = Color(0xFF090F0C),
    surfaceContainerLow = Color(0xFF151E19),
    surfaceContainer = Color(0xFF1A251F),
    surfaceContainerHigh = Color(0xFF253129),
    surfaceContainerHighest = Color(0xFF303D34),
    outline = Color(0xFF84958B),
    outlineVariant = Color(0xFF3B4941),
    inverseSurface = Color(0xFFE2E9E3),
    inverseOnSurface = Color(0xFF2D3832),
    inversePrimary = Color(0xFF1B5E4B),
)

private val PrivvyShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val PrivvyTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
)

object PrivvySpacing {
    val screen = 20.dp
    val section = 24.dp
    val card = 18.dp
    val item = 12.dp
}

@Composable
fun PrivvyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = PrivvyShapes,
        typography = PrivvyTypography,
        content = content,
    )
}
