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

/** Resolves this [FacetIcon] category to the Material glyph shown in a genre hero's scallop badge. */
fun FacetIcon.toImageVector(): ImageVector =
    when (this) {
        FacetIcon.FANTASY -> Icons.Filled.Castle
        FacetIcon.SCIFI -> Icons.Filled.RocketLaunch
        FacetIcon.MYSTERY -> Icons.Filled.Search
        FacetIcon.ROMANCE -> Icons.Filled.Favorite
        FacetIcon.HORROR -> Icons.Filled.DarkMode
        FacetIcon.HISTORY -> Icons.Filled.History
        FacetIcon.BIOGRAPHY -> Icons.Filled.Person
        FacetIcon.BUSINESS -> Icons.Filled.AccountBalance
        FacetIcon.SCIENCE -> Icons.Filled.Science
        FacetIcon.SELF_HELP -> Icons.Filled.SelfImprovement
        FacetIcon.CHILDREN -> Icons.Filled.ChildCare
        FacetIcon.YOUNG_ADULT -> Icons.Filled.School
        FacetIcon.HEALTH -> Icons.Filled.HealthAndSafety
        FacetIcon.FOOD -> Icons.Filled.Restaurant
        FacetIcon.TRAVEL -> Icons.Filled.Flight
        FacetIcon.POETRY -> Icons.Filled.AutoAwesome
        FacetIcon.LITERARY -> Icons.Filled.AutoStories
        FacetIcon.RELIGION -> Icons.Filled.Church
        FacetIcon.ART -> Icons.Filled.Palette
        FacetIcon.MUSIC -> Icons.Filled.MusicNote
        FacetIcon.COMIC -> Icons.Filled.BrushIcon
        FacetIcon.ANTHOLOGY -> Icons.AutoMirrored.Filled.LibraryBooks
        FacetIcon.HUMOR -> Icons.Filled.TheaterComedy
        FacetIcon.POLITICS -> Icons.Filled.Gavel
        FacetIcon.TECH -> Icons.Filled.Computer
        FacetIcon.SPORT -> Icons.Filled.SportsBasketball
        FacetIcon.DEFAULT -> Icons.AutoMirrored.Filled.MenuBook
    }

/** Parses a genre's accent hue (hex string, e.g. `"#2E5AA0"`, from `FacetIdentity.hue`) into a Compose [Color]. */
fun genreHueColor(hue: String): Color = parseHexColor(hue)

/** Mixes [color] toward black by [amount] (0f–1f) — the dark endpoint of a genre hero's gradient. */
fun darken(
    color: Color,
    amount: Float = 0.42f,
): Color = lerp(color, Color.Black, amount)
