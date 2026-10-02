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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The admin inbox's glyph on an amber tile: the inbox icon in onTertiaryContainer on a rounded
 * tertiaryContainer square. Amber is "waiting for you" across the app; coral stays "act here".
 *
 * Decoration only — the glyph carries no content description, so the row or section it heads
 * names itself. One recipe for the Library's inbox entry and Book Detail's held section.
 *
 * @param size the tile's width and height
 * @param corner the tile's corner radius
 * @param glyphSize the inbox icon's size inside the tile
 */
@Composable
fun InboxGlyphTile(
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 24.dp,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(corner))
                .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Inbox,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(glyphSize),
        )
    }
}
