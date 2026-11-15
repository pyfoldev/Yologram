package com.pyfold.ubuntucontrol

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Aubergine = Color(0xFF2C001E)
val AubergineLight = Color(0xFF3E0A2E)
val Panel = Color(0xFF4A1238)
val UbuntuOrange = Color(0xFFE95420)
val TextDim = Color(0xFFD9B9CB)
val Good = Color(0xFF7BD88F)
val Bad = Color(0xFFFF6B6B)

private val scheme = darkColorScheme(
    primary = UbuntuOrange, onPrimary = Color.White,
    background = Aubergine, onBackground = Color.White,
    surface = Panel, onSurface = Color.White,
    surfaceVariant = AubergineLight, onSurfaceVariant = TextDim,
    secondary = Color(0xFFAEA79F), error = Bad,
)

@Composable
fun UbuntuTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
