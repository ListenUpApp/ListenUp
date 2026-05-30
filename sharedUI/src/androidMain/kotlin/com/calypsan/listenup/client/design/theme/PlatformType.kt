package com.calypsan.listenup.client.design.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.composeapp.R

/**
 * Google Sans Flex variable font family.
 * Supports dynamic weight and width adjustments for expressive typography.
 */
@OptIn(ExperimentalTextApi::class)
internal val GoogleSans =
    FontFamily(
        Font(
            resId = R.font.google_sans,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(400),
                    FontVariation.width(100f),
                ),
        ),
    )

/**
 * Condensed display variant for headlines.
 * Slightly tighter width (95f) creates a premium, editorial feel.
 * Used for display and headline styles to establish visual hierarchy.
 */
@OptIn(ExperimentalTextApi::class)
internal val GoogleSansDisplay =
    FontFamily(
        Font(
            resId = R.font.google_sans,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(600),
                    FontVariation.width(95f),
                ),
        ),
    )

/**
 * Heavier display instance for emphasized roles — genuinely bolder than the
 * standard display family so emphasized text reads with extra weight.
 */
@OptIn(ExperimentalTextApi::class)
internal val GoogleSansDisplayEmphasized =
    FontFamily(
        Font(
            resId = R.font.google_sans,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(760),
                    FontVariation.width(95f),
                ),
        ),
    )

/** Heavier standard instance for emphasized title/label roles. */
@OptIn(ExperimentalTextApi::class)
internal val GoogleSansEmphasized =
    FontFamily(
        Font(
            resId = R.font.google_sans,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(680),
                    FontVariation.width(100f),
                ),
        ),
    )

/**
 * ListenUp typography system using Google Sans Flex.
 * Follows Material 3 Expressive type scale with custom font.
 */
actual val DisplayFontFamily: FontFamily = GoogleSansDisplay

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
actual val ListenUpTypography =
    Typography(
        // Display - Hero text, large headlines (condensed for editorial feel)
        displayLarge =
            TextStyle(
                fontFamily = GoogleSansDisplay,
                fontWeight = FontWeight.Bold,
                fontSize = 57.sp,
                lineHeight = 64.sp,
                letterSpacing = (-0.25).sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = GoogleSansDisplay,
                fontWeight = FontWeight.Bold,
                fontSize = 45.sp,
                lineHeight = 52.sp,
                letterSpacing = (-0.25).sp,
            ),
        // Headline - Screen titles, section headers (standard width, tight tracking for editorial feel)
        headlineLarge =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                letterSpacing = (-0.5).sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                letterSpacing = (-0.3).sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Medium,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                letterSpacing = (-0.2).sp,
            ),
        // Title - List items, card headers
        titleLarge =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Medium,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        // Body - Main content text
        bodyLarge =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.25.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.4.sp,
            ),
        // Label - Buttons, tabs, chips
        labelLarge =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
        // Emphasized roles (M3 Expressive) — heavier variants for hero/title/header text
        displayLargeEmphasized =
            TextStyle(
                fontFamily = GoogleSansDisplayEmphasized,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp,
            ),
        displayMediumEmphasized =
            TextStyle(
                fontFamily = GoogleSansDisplayEmphasized,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = (-0.25).sp,
            ),
        headlineLargeEmphasized =
            TextStyle(
                fontFamily = GoogleSansDisplayEmphasized,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp,
            ),
        headlineMediumEmphasized =
            TextStyle(
                fontFamily = GoogleSansDisplayEmphasized,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp,
            ),
        titleLargeEmphasized =
            TextStyle(
                fontFamily = GoogleSansEmphasized,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp, lineHeight = 28.sp,
            ),
        labelLargeEmphasized =
            TextStyle(
                fontFamily = GoogleSansEmphasized,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
            ),
    )
