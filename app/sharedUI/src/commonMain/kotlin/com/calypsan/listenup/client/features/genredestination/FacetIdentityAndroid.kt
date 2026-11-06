package com.calypsan.listenup.client.features.genredestination

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import com.calypsan.listenup.client.design.icons.ListenUpIcons
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
        FacetIcon.FANTASY to ListenUpIcons.CastleFilled,
        FacetIcon.SCIFI to ListenUpIcons.RocketLaunchFilled,
        FacetIcon.MYSTERY to ListenUpIcons.SearchFilled,
        FacetIcon.ROMANCE to ListenUpIcons.FavoriteFilled,
        FacetIcon.HORROR to ListenUpIcons.DarkModeFilled,
        FacetIcon.HISTORY to ListenUpIcons.HistoryFilled,
        FacetIcon.BIOGRAPHY to ListenUpIcons.PersonFilled,
        FacetIcon.BUSINESS to ListenUpIcons.AccountBalanceFilled,
        FacetIcon.SCIENCE to ListenUpIcons.ScienceFilled,
        FacetIcon.SELF_HELP to ListenUpIcons.SelfImprovementFilled,
        FacetIcon.CHILDREN to ListenUpIcons.ChildCareFilled,
        FacetIcon.YOUNG_ADULT to ListenUpIcons.SchoolFilled,
        FacetIcon.HEALTH to ListenUpIcons.HealthAndSafetyFilled,
        FacetIcon.FOOD to ListenUpIcons.RestaurantFilled,
        FacetIcon.TRAVEL to ListenUpIcons.FlightFilled,
        FacetIcon.POETRY to ListenUpIcons.AutoAwesomeFilled,
        FacetIcon.LITERARY to ListenUpIcons.AutoStoriesFilled,
        FacetIcon.RELIGION to ListenUpIcons.ChurchFilled,
        FacetIcon.ART to ListenUpIcons.PaletteFilled,
        FacetIcon.MUSIC to ListenUpIcons.MusicNoteFilled,
        FacetIcon.COMIC to ListenUpIcons.BrushFilled,
        FacetIcon.ANTHOLOGY to ListenUpIcons.LibraryBooksFilled,
        FacetIcon.HUMOR to ListenUpIcons.TheaterComedyFilled,
        FacetIcon.POLITICS to ListenUpIcons.GavelFilled,
        FacetIcon.TECH to ListenUpIcons.ComputerFilled,
        FacetIcon.SPORT to ListenUpIcons.SportsBasketballFilled,
        FacetIcon.DEFAULT to ListenUpIcons.MenuBookFilled,
    )

/** Resolves this [FacetIcon] category to the Material glyph shown in a genre hero's scallop badge. */
fun FacetIcon.toImageVector(): ImageVector = FACET_GLYPHS[this] ?: ListenUpIcons.MenuBookFilled

/** Parses a genre's accent hue (hex string, e.g. `"#2E5AA0"`, from `FacetIdentity.hue`) into a Compose [Color]. */
fun genreHueColor(hue: String): Color = parseHexColor(hue)

/** Mixes [color] toward black by [amount] (0f–1f) — the dark endpoint of a genre hero's gradient. */
fun darken(
    color: Color,
    amount: Float = 0.42f,
): Color = lerp(color, Color.Black, amount)
