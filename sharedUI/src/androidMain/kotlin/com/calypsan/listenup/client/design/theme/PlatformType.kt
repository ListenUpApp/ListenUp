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
 * One Google Sans Flex instance at a fixed [weight] / [width] on the `wght` / `wdth` axes.
 *
 * The family registers one of these per weight so Compose's matcher resolves a requested
 * [FontWeight] to the entry that *also* carries the matching `wght` variation. Without this, a
 * single fixed-variation font renders every requested weight identically and the type scale
 * collapses to one weight (the bug this replaces).
 */
@OptIn(ExperimentalTextApi::class)
private fun googleSansFont(
    weight: FontWeight,
    width: Float,
): Font =
    Font(
        resId = R.font.google_sans,
        weight = weight,
        variationSettings =
            FontVariation.Settings(
                FontVariation.weight(weight.weight),
                FontVariation.width(width),
            ),
    )

private const val WIDTH_STANDARD = 100f

/** Slightly condensed width for display + headline roles — a premium, editorial feel. */
private const val WIDTH_CONDENSED = 95f

/**
 * Standard-width Google Sans Flex for titles, body, and labels. Registers the full weight ramp so
 * `fontWeight` (and `.copy(fontWeight = …)`) actually drive the variable `wght` axis.
 */
internal val GoogleSans =
    FontFamily(
        googleSansFont(FontWeight.Normal, WIDTH_STANDARD),
        googleSansFont(FontWeight.Medium, WIDTH_STANDARD),
        googleSansFont(FontWeight.SemiBold, WIDTH_STANDARD),
        googleSansFont(FontWeight.Bold, WIDTH_STANDARD),
        googleSansFont(FontWeight.ExtraBold, WIDTH_STANDARD),
    )

/** Condensed Google Sans Flex for display + headline roles, with the heaviest weights available. */
internal val GoogleSansDisplay =
    FontFamily(
        googleSansFont(FontWeight.SemiBold, WIDTH_CONDENSED),
        googleSansFont(FontWeight.Bold, WIDTH_CONDENSED),
        googleSansFont(FontWeight.ExtraBold, WIDTH_CONDENSED),
    )

actual val DisplayFontFamily: FontFamily = GoogleSansDisplay

/**
 * ListenUp typography — Material 3 Expressive scale on Google Sans Flex, matched to the design
 * system's weight ramp:
 *
 *  - body **400**, title/label **600**, headline **700**, and the Expressive `*Emphasized` roles
 *    **800** for heroes, section headers, and big stat numbers.
 *
 * Bold headings over regular body give the clear, scannable hierarchy the design draws the eye with.
 */
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
        displaySmall =
            TextStyle(
                fontFamily = GoogleSansDisplay,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                lineHeight = 44.sp,
                letterSpacing = (-0.25).sp,
            ),
        // Headline - Screen titles, section headers (condensed, tight tracking; design weight 700)
        headlineLarge =
            TextStyle(
                fontFamily = GoogleSansDisplay,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                letterSpacing = (-0.5).sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = GoogleSansDisplay,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                letterSpacing = (-0.3).sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = GoogleSansDisplay,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                letterSpacing = (-0.2).sp,
            ),
        // Title - List items, card headers (design weight 600)
        titleLarge =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.1.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        // Body - Main content text (design weight 400)
        bodyLarge =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.2.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.4.sp,
            ),
        // Label - Buttons, tabs, chips (design weight 600)
        labelLarge =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
        // Emphasized roles (M3 Expressive) — ExtraBold for heroes, section headers, big numbers
        displayLargeEmphasized =
            TextStyle(
                fontFamily = GoogleSansDisplay,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 57.sp,
                lineHeight = 64.sp,
                letterSpacing = (-0.25).sp,
            ),
        displayMediumEmphasized =
            TextStyle(
                fontFamily = GoogleSansDisplay,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 45.sp,
                lineHeight = 52.sp,
                letterSpacing = (-0.5).sp,
            ),
        headlineLargeEmphasized =
            TextStyle(
                fontFamily = GoogleSansDisplay,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                letterSpacing = (-0.5).sp,
            ),
        headlineMediumEmphasized =
            TextStyle(
                fontFamily = GoogleSansDisplay,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                letterSpacing = (-0.3).sp,
            ),
        titleLargeEmphasized =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        labelLargeEmphasized =
            TextStyle(
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
    )
