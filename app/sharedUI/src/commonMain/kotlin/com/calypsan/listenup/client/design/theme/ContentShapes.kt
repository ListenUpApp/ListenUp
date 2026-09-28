package com.calypsan.listenup.client.design.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Shape tokens for content tiles — cover art, shelf cards, and similar surfaces.
 *
 * Softer than Material's default container corners for a more expressive, modern feel. Kept separate
 * from the Material [androidx.compose.material3.Shapes] scale so component corners can be retuned in
 * one place without shifting Material's own role-based shapes (buttons, sheets, menus).
 */
object ContentShapes {
    /** Cover art, shelf cards, and other content tiles. */
    val card = RoundedCornerShape(24.dp)

    /**
     * The one color-block hero edge: square top (it bleeds under the status bar), 28dp rounded
     * bottom — the `large` step of the shape scale, which DESIGN.md binds hero bands to. Every hero
     * uses this, directly or through
     * [ColorBlockHero][com.calypsan.listenup.client.design.components.ColorBlockHero].
     */
    val hero = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
}
