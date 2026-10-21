package xyz.tiresia.kavana.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Material 3 in Kavana's teal (the web's --tint, #2C8A7E light and #5FD0C0 dark), light and dark.

private val Light = lightColorScheme(
    primary = Color(0xFF2C8A7E), onPrimary = Color.White, primaryContainer = Color(0xFFD3EFEA), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A6360), onSecondary = Color.White, secondaryContainer = Color(0xFFCCE8E3), onSecondaryContainer = Color(0xFF05201D),
    tertiary = Color(0xFF8A5100), onTertiary = Color.White, tertiaryContainer = Color(0xFFFFDCBE), onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFF4F6F5), onBackground = Color(0xFF161D1C), surface = Color(0xFFF4F6F5), onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E2), onSurfaceVariant = Color(0xFF3F4947), outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C6),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEEF1F0), surfaceContainer = Color(0xFFE8ECEA),
    surfaceContainerHigh = Color(0xFFE2E6E4), surfaceContainerHighest = Color(0xFFDCE1DF),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5FD0C0), onPrimary = Color(0xFF04201C), primaryContainer = Color(0xFF005048), onPrimaryContainer = Color(0xFFA6F2E5),
    secondary = Color(0xFFB1CCC7), onSecondary = Color(0xFF1C3532), secondaryContainer = Color(0xFF334B48), onSecondaryContainer = Color(0xFFCCE8E3),
    tertiary = Color(0xFFFFB870), onTertiary = Color(0xFF4A2800), tertiaryContainer = Color(0xFF6A3B00), onTertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF0E1312), onBackground = Color(0xFFDDE4E2), surface = Color(0xFF0E1312), onSurface = Color(0xFFDDE4E2),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBEC9C6), outline = Color(0xFF889391), outlineVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFF090E0D), surfaceContainerLow = Color(0xFF161D1C), surfaceContainer = Color(0xFF1A2120),
    surfaceContainerHigh = Color(0xFF242B2A), surfaceContainerHighest = Color(0xFF2F3635),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

/** The web's system colours for the round buttons and the tiles. */
@Immutable
data class KColors(val orange: Color, val green: Color, val red: Color, val pink: Color, val blue: Color, val indigo: Color, val mint: Color, val gray: Color)

private val LightK = KColors(Color(0xFFFF9500), Color(0xFF34C759), Color(0xFFFF3B30), Color(0xFFFF2D55), Color(0xFF007AFF), Color(0xFF5856D6), Color(0xFF00C7BE), Color(0xFF8E8E93))
private val DarkK = KColors(Color(0xFFFF9F0A), Color(0xFF30D158), Color(0xFFFF453A), Color(0xFFFF375F), Color(0xFF0A84FF), Color(0xFF5E5CE6), Color(0xFF63E6E2), Color(0xFF8E8E93))

val LocalK = staticCompositionLocalOf { LightK }

@Composable
fun KavanaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val base = MaterialTheme.typography
    val type = base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.4).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(LocalK provides if (dark) DarkK else LightK) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = type, content = content)
    }
}
