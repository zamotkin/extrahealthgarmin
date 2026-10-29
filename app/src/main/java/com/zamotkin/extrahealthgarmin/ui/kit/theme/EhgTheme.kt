package com.zamotkin.extrahealthgarmin.ui.kit.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The single source of design tokens for the whole app (redesign v3, task
 * xlg.1). Material 3 with dynamic color on Android 12+ and a health-brand
 * teal/green fallback palette below that — full light and dark schemes, so
 * pre-S devices and the XML window theme share one brand look.
 *
 * Semantic tokens that must never follow the wallpaper stay FIXED: the error
 * roles keep their baseline values in both fallback schemes and dynamic
 * color supplies Material-certified error tones on 12+.
 *
 * The "2026 expressive" styling comes from stable material3 1.4.0 APIs only
 * (color + generous shapes + the default spring motion) — no experimental
 * expressive components.
 */
@Composable
fun EhgTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Parameter (not a constant) so previews and tests can pin the brand
    // palette even on Android 12+ devices.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> EhgDarkColors
        else -> EhgLightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = EhgTypography,
        shapes = EhgShapes,
        content = content,
    )
}

/** Brand fallback, light scheme (teal seed, Material 3 tonal palette). */
private val EhgLightColors = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EF2E4),
    onPrimaryContainer = Color(0xFF00201C),
    inversePrimary = Color(0xFF82D5C9),
    secondary = Color(0xFF4A635E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E1),
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Color(0xFF456179),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCE5FF),
    onTertiaryContainer = Color(0xFF001E31),
    background = Color(0xFFF5FBF8),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF5FBF8),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBEC9C5),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2B3230),
    inverseOnSurface = Color(0xFFECF2EF),
    surfaceDim = Color(0xFFD5DBD8),
    surfaceBright = Color(0xFFF5FBF8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F2),
    surfaceContainer = Color(0xFFE9EFEC),
    surfaceContainerHigh = Color(0xFFE3EAE7),
    surfaceContainerHighest = Color(0xFFDEE4E1),
)

/** Brand fallback, dark scheme (same teal seed, dark tonal palette). */
private val EhgDarkColors = darkColorScheme(
    primary = Color(0xFF82D5C9),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF9EF2E4),
    inversePrimary = Color(0xFF006A60),
    secondary = Color(0xFFB1CCC5),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF334B46),
    onSecondaryContainer = Color(0xFFCCE8E1),
    tertiary = Color(0xFFADCAE6),
    onTertiary = Color(0xFF143349),
    tertiaryContainer = Color(0xFF2C4961),
    onTertiaryContainer = Color(0xFFCCE5FF),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFDEE4E1),
    inverseOnSurface = Color(0xFF2B3230),
    surfaceDim = Color(0xFF0E1513),
    surfaceBright = Color(0xFF343B39),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF161D1B),
    surfaceContainer = Color(0xFF1A2120),
    surfaceContainerHigh = Color(0xFF242B29),
    surfaceContainerHighest = Color(0xFF2F3634),
)

/**
 * Expressive type scale on stable [Typography]: oversized display/headline
 * styles go light (the M3 expressive signature), titles stay semibold for
 * emphasis; every other role keeps the Material 3 default.
 */
private val EhgTypography = with(Typography()) {
    Typography(
        displayLarge = displayLarge.copy(fontWeight = FontWeight.W300),
        displayMedium = displayMedium.copy(fontWeight = FontWeight.W300),
        displaySmall = displaySmall.copy(fontWeight = FontWeight.W300),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.W300),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.W400),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.W400),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Generous rounding — the expressive shape language for cards and controls. */
private val EhgShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
