package com.mamre.billing.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun style(size: Int, weight: FontWeight, line: Int) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
)

/**
 * Default font. Roles: titleLarge is the screen title (22 bold), titleMedium the section
 * heading (16 semibold), bodyLarge and bodyMedium the body (16), bodySmall and the label
 * styles the label (14), headlineSmall, headlineMedium and headlineLarge the amounts
 * (20, 24 and 28 bold).
 */
val Typography = Typography(
    headlineLarge = style(28, FontWeight.Bold, 34),
    headlineMedium = style(24, FontWeight.Bold, 30),
    headlineSmall = style(20, FontWeight.Bold, 26),
    titleLarge = style(22, FontWeight.Bold, 28),
    titleMedium = style(16, FontWeight.SemiBold, 22),
    titleSmall = style(14, FontWeight.SemiBold, 20),
    bodyLarge = style(16, FontWeight.Normal, 24),
    bodyMedium = style(16, FontWeight.Normal, 24),
    bodySmall = style(14, FontWeight.Normal, 20),
    labelLarge = style(14, FontWeight.Medium, 20),
    labelMedium = style(14, FontWeight.Medium, 20),
    labelSmall = style(14, FontWeight.Medium, 20),
)
