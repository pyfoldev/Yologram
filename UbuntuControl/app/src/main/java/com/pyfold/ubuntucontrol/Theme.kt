package com.pyfold.ubuntucontrol

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Чёрная (AMOLED) тема Material 3 с акцентом Ubuntu.
val UbuntuOrange = Color(0xFFE95420)
val OrangeSoft = Color(0xFFFF8A5C)
val Good = Color(0xFF6FE08A)
val Bad = Color(0xFFFF6B6B)
val Warn = Color(0xFFFFC857)
val TextDim = Color(0xFFA6A6AD)

private val scheme = darkColorScheme(
    primary = UbuntuOrange, onPrimary = Color.White,
    primaryContainer = Color(0xFF4A1F0D), onPrimaryContainer = Color(0xFFFFDBCD),
    secondary = Color(0xFFC9C5CA), onSecondary = Color.Black,
    secondaryContainer = Color(0xFF26262A), onSecondaryContainer = Color(0xFFE6E1E5),
    tertiary = OrangeSoft,
    background = Color.Black, onBackground = Color(0xFFF2F2F5),
    surface = Color.Black, onSurface = Color(0xFFF2F2F5),
    surfaceVariant = Color(0xFF1C1C20), onSurfaceVariant = TextDim,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0C0C0E),
    surfaceContainer = Color(0xFF121215),
    surfaceContainerHigh = Color(0xFF1A1A1E),
    surfaceContainerHighest = Color(0xFF232328),
    outline = Color(0xFF3A3A40), outlineVariant = Color(0xFF26262B),
    error = Bad, onError = Color.Black, errorContainer = Color(0xFF3B1212), onErrorContainer = Color(0xFFFFDAD6),
)

private val type = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun UbuntuTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
