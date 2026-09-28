package com.calypsan.listenup.client.design.theme

import androidx.compose.ui.unit.dp

/**
 * ListenUp spacing tokens — the vertical/horizontal rhythm the design is built on.
 *
 * Material 3 ships a type scale but no spacing scale, so this is where the app's layout rhythm
 * lives. Screens reference these instead of hardcoding dp values, so the rhythm stays uniform and
 * can be retuned in one place. Tuned against the design mockups (allowing for our different font).
 *
 * Two layers, and the choice between them is about *role*:
 * - **Semantic names** ([screenMargin], [sectionGap], [titleGap], [itemGap]) say what the space is
 *   for. Reach for one whenever the space plays that role — a page's edge, the break between two
 *   sections — so retuning the role retunes every screen at once.
 * - **Scale steps** ([xs] to [xxl]) are the raw rhythm on a 4dp base. Use one for everything else:
 *   a card's inner padding, the gap inside a row, the air around a divider. A value that isn't on
 *   the scale is a deliberate optical exception and should look like one.
 *
 * Geometry is not spacing: icon, cover and touch-target sizes, stroke widths, corner radii and
 * drawing offsets stay raw dp.
 */
object Spacing {
    /** 4dp — the hairline step: between a label and its caption, a glyph and its text. */
    val xs = 4.dp

    /** 8dp — tight grouping: chips in a row, a row's icon and its text. */
    val sm = 8.dp

    /** 12dp — the related-items step, and the base of [titleGap] and [itemGap]. */
    val md = 12.dp

    /** 16dp — a card's or row's inner padding; the compact list inset. */
    val lg = 16.dp

    /** 24dp — the page step, and the base of [screenMargin] and [sectionGap]. */
    val xl = 24.dp

    /** 32dp — generous separation: an empty state's padding, a hero's breathing room. */
    val xxl = 32.dp

    /** Horizontal page margin — where section titles, headers, and rows start. */
    val screenMargin = xl

    /** Vertical gap between major page sections (e.g. Continue Listening → This Week). */
    val sectionGap = xl

    /** Gap between a section title and the content it heads. */
    val titleGap = md

    /** Gap between sibling items in a horizontal row (e.g. cover cards). */
    val itemGap = md
}
