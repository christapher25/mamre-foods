package com.mamre.billing.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale: 4, 8, 12, 16, 24 dp. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/** Sizes: touch targets are at least 48 dp, primary buttons 56 dp (Doc 2 s10 UX rules). */
object Sizes {
    val touchTarget: Dp = 48.dp
    val primaryButton: Dp = 56.dp
    val cornerRadius: Dp = 12.dp
}

/** Colours the Material scheme has no slot for. */
@Immutable
class ExtraColors(
    val success: Color,
    val successContainer: Color,
    val onSurfaceMuted: Color,
    val disabledContainer: Color,
    val onDisabled: Color,
)

private val LocalExtraColors = staticCompositionLocalOf {
    ExtraColors(
        BrandSuccess,
        BrandSuccessContainer,
        BrandOnSurfaceMuted,
        BrandDisabledContainer,
        BrandOnDisabled,
    )
}

/** Read the extra colours as `MamreTheme.extra.success`. */
object MamreTheme {
    val extra: ExtraColors
        @Composable @ReadOnlyComposable get() = LocalExtraColors.current
}

private val LightColorScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandOnPrimary,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = BrandOnPrimaryContainer,
    secondary = BrandSecondary,
    onSecondary = BrandOnPrimary,
    background = BrandBackground,
    onBackground = BrandOnSurface,
    surface = BrandSurface,
    onSurface = BrandOnSurface,
    surfaceVariant = BrandBackground,
    onSurfaceVariant = BrandOnSurfaceMuted,
    outline = BrandOutline,
    outlineVariant = BrandOutline,
    error = BrandError,
    onError = BrandOnPrimary,
    errorContainer = BrandErrorContainer,
    onErrorContainer = BrandError,
)

private val MamreShapes = Shapes(
    extraSmall = RoundedCornerShape(Sizes.cornerRadius),
    small = RoundedCornerShape(Sizes.cornerRadius),
    medium = RoundedCornerShape(Sizes.cornerRadius),
    large = RoundedCornerShape(Sizes.cornerRadius),
    extraLarge = RoundedCornerShape(Sizes.cornerRadius),
)

/** Material 3, light theme only, no dynamic colour (the brand palette must always show). */
@Composable
fun MamreBillingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        shapes = MamreShapes,
        content = content,
    )
}
