package com.calypsan.listenup.client.features.genredestination

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Brush as BrushIcon
import androidx.compose.material.icons.filled.Castle
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Church
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import com.calypsan.listenup.client.design.util.parseHexColor
import com.calypsan.listenup.client.presentation.genredestination.FacetIcon

/*
 * Compose-side binding of the platform-neutral FacetIcon category (sharedLogic, defined once
 * in commonMain across every platform) to a concrete Material glyph, plus small colour helpers for
 * rendering a genre's accent hue. Kept out of the shared FacetIdentity object deliberately — the
 * icon *set* is a per-platform choice (iOS binds the same FacetIcon enum to SF Symbols), so only
 * the category name is shared, not the glyph.
 */

// 1:1 category→glyph lookup table (a `when` over all 27 cases trips detekt's complexity metric even
// though it's a flat mapping; a map keeps it a plain data structure). A future [FacetIcon] with no
// entry falls back to the book glyph rather than crashing.
private val FACET_GLYPHS: Map<FacetIcon, ImageVector> =
    mapOf(
        FacetIcon.FANTASY to Icons.Filled.Castle,
        FacetIcon.SCIFI to Icons.Filled.RocketLaunch,
        FacetIcon.MYSTERY to Icons.Filled.Search,
        FacetIcon.ROMANCE to Icons.Filled.Favorite,
        FacetIcon.HORROR to Icons.Filled.DarkMode,
        FacetIcon.HISTORY to Icons.Filled.History,
        FacetIcon.BIOGRAPHY to Icons.Filled.Person,
        FacetIcon.BUSINESS to Icons.Filled.AccountBalance,
        FacetIcon.SCIENCE to Icons.Filled.Science,
        FacetIcon.SELF_HELP to Icons.Filled.SelfImprovement,
        FacetIcon.CHILDREN to Icons.Filled.ChildCare,
        FacetIcon.YOUNG_ADULT to Icons.Filled.School,
        FacetIcon.HEALTH to Icons.Filled.HealthAndSafety,
        FacetIcon.FOOD to Icons.Filled.Restaurant,
        FacetIcon.TRAVEL to Icons.Filled.Flight,
        FacetIcon.POETRY to Icons.Filled.AutoAwesome,
        FacetIcon.LITERARY to Icons.Filled.AutoStories,
        FacetIcon.RELIGION to Icons.Filled.Church,
        FacetIcon.ART to Icons.Filled.Palette,
        FacetIcon.MUSIC to Icons.Filled.MusicNote,
        FacetIcon.COMIC to Icons.Filled.BrushIcon,
        FacetIcon.ANTHOLOGY to Icons.AutoMirrored.Filled.LibraryBooks,
        FacetIcon.HUMOR to Icons.Filled.TheaterComedy,
        FacetIcon.POLITICS to Icons.Filled.Gavel,
        FacetIcon.TECH to Icons.Filled.Computer,
        FacetIcon.SPORT to Icons.Filled.SportsBasketball,
        FacetIcon.DEFAULT to Icons.AutoMirrored.Filled.MenuBook,
    )

/** Resolves this [FacetIcon] category to the Material glyph shown in a genre hero's scallop badge. */
fun FacetIcon.toImageVector(): ImageVector = FACET_GLYPHS[this] ?: Icons.AutoMirrored.Filled.MenuBook

/** Parses a genre's accent hue (hex string, e.g. `"#2E5AA0"`, from `FacetIdentity.hue`) into a Compose [Color]. */
fun genreHueColor(hue: String): Color = parseHexColor(hue)

/** Mixes [color] toward black by [amount] (0f–1f) — the dark endpoint of a genre hero's gradient. */
fun darken(
    color: Color,
    amount: Float = 0.42f,
): Color = lerp(color, Color.Black, amount)
