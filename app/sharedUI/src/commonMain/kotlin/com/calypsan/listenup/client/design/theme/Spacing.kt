package com.calypsan.listenup.client.design.theme

import androidx.compose.ui.unit.dp

/**
 * ListenUp spacing tokens — the vertical/horizontal rhythm the design is built on.
 *
 * Material 3 ships a type scale but no spacing scale, so this is where the app's layout rhythm
 * lives. Screens reference these instead of hardcoding dp values, so the rhythm stays uniform and
 * can be retuned in one place. Tuned against the design mockups (allowing for our different font).
 */
object Spacing {
    /** Horizontal page margin — where section titles, headers, and rows start. */
    val screenMargin = 24.dp

    /** Vertical gap between major page sections (e.g. Continue Listening → This Week). */
    val sectionGap = 24.dp

    /** Gap between a section title and the content it heads. */
    val titleGap = 12.dp

    /** Gap between sibling items in a horizontal row (e.g. cover cards). */
    val itemGap = 12.dp
}
