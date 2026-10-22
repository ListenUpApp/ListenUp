package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ListenUpExtendedColors
import com.calypsan.listenup.client.design.theme.extendedColors

/**
 * The admin inbox's glyph on an amber tile: the inbox icon in the fixed held amber
 * ([ListenUpExtendedColors.heldContainer]), which no wallpaper changes. Amber is "waiting for you" across the app; coral stays "act here".
 *
 * Decoration only — the glyph carries no content description, so the row or section it heads
 * names itself. One recipe for the Library's inbox entry and Book Detail's held section.
 *
 * @param size the tile's width and height
 * @param corner the tile's corner radius
 * @param glyphSize the inbox icon's size inside the tile
 * @param onAmber the tile sits on an amber (heldContainer) surface, so it takes the stronger amber role to stay
 *   visible (spec a6's amber glyph on the Library's inbox strip)
 */
@Composable
fun InboxGlyphTile(
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 24.dp,
    onAmber: Boolean = false,
) {
    val colors = inboxGlyphColors(MaterialTheme.extendedColors, onAmber)
    Box(
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(corner))
                .background(colors.tile),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Inbox,
            contentDescription = null,
            tint = colors.glyph,
            modifier = Modifier.size(glyphSize),
        )
    }
}

/** An inbox tile's colours: the tile and the glyph on it. */
internal data class InboxGlyphColors(
    val tile: Color,
    val glyph: Color,
)

/** Amber on a plain surface; the stronger amber role on an amber one, where a heldContainer tile would vanish. */
internal fun inboxGlyphColors(
    held: ListenUpExtendedColors,
    onAmber: Boolean,
): InboxGlyphColors =
    if (onAmber) {
        InboxGlyphColors(tile = held.held, glyph = held.onHeld)
    } else {
        InboxGlyphColors(tile = held.heldContainer, glyph = held.onHeldContainer)
    }
