package ru.reader.app

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Violet = Color(0xFF6A3FE0)

private fun light() = lightColorScheme(
    primary = Violet, onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DEFF), onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF5E5877), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4DFF9), onSecondaryContainer = Color(0xFF1A1530),
    tertiary = Color(0xFF8A4FFF), onTertiary = Color.White,
    background = Color.White, onBackground = Color(0xFF0C0A12),
    surface = Color.White, onSurface = Color(0xFF0C0A12),
    surfaceVariant = Color(0xFFEFEAF7), onSurfaceVariant = Color(0xFF494556),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F5FD),
    surfaceContainer = Color(0xFFF3EFFA), surfaceContainerHigh = Color(0xFFEDE8F6), surfaceContainerHighest = Color(0xFFE7E1F1),
    outline = Color(0xFF7A7589), outlineVariant = Color(0xFFD0CADD),
    error = Color(0xFFBA1A1A),
)

private fun dark(amoled: Boolean) = darkColorScheme(
    primary = Color(0xFFBCA2FF), onPrimary = Color(0xFF2B0B72),
    primaryContainer = Color(0xFF4B2CB0), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCAC1E3), onSecondary = Color(0xFF312B48),
    secondaryContainer = Color(0xFF3A3354), onSecondaryContainer = Color(0xFFE6DFFF),
    tertiary = Color(0xFFD0B8FF), onTertiary = Color(0xFF3B1A85),
    background = if (amoled) Color.Black else Color(0xFF110F16),
    onBackground = Color(0xFFF2EEFA),
    surface = if (amoled) Color.Black else Color(0xFF110F16), onSurface = Color(0xFFF2EEFA),
    surfaceVariant = Color(0xFF2A2633), onSurfaceVariant = Color(0xFFCBC5D8),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = if (amoled) Color(0xFF0B0A0F) else Color(0xFF17141D),
    surfaceContainer = if (amoled) Color(0xFF121017) else Color(0xFF1C1923),
    surfaceContainerHigh = if (amoled) Color(0xFF1A1722) else Color(0xFF26222E),
    surfaceContainerHighest = if (amoled) Color(0xFF241F2E) else Color(0xFF302B39),
    outline = Color(0xFF948EA3), outlineVariant = Color(0xFF3A3547),
    error = Color(0xFFFFB4AB),
)

private val base = Typography()
private val AppTypography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = base.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AppTheme(darkTheme: Boolean, amoled: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) dark(amoled) else light(),
        typography = AppTypography, shapes = AppShapes, content = content,
    )
}
