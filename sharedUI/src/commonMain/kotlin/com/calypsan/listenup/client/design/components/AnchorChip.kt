package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CHIP_HEIGHT = 28.dp
private val CHIP_PADDING = PaddingValues(start = 12.dp, end = 9.dp)
private val ICON_SIZE = 15.dp
private val LABEL_SIZE = 12.5.sp

/**
 * A small pill showing where a Story World entry (or an anchor picker choice) sits in a book's
 * timeline — "Ch. 4 · 12m in", "Beginning of the book" — with a leading clock glyph. [muted]
 * switches between the emphasized `tertiaryContainer` treatment (the entry's own reveal anchor)
 * and a quieter `surfaceContainerHigh` treatment (secondary timing context, e.g. in a list row).
 *
 * @param label The anchor description to display.
 * @param modifier Modifier for the chip.
 * @param muted When true, uses the quieter neutral-surface treatment instead of tertiary.
 */
@Composable
fun AnchorChip(
    label: String,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    val containerColor =
        if (muted) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.tertiaryContainer
    val contentColor =
        if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onTertiaryContainer

    Row(
        modifier =
            modifier
                .height(CHIP_HEIGHT)
                .clip(RoundedCornerShape(percent = 50))
                .background(containerColor)
                .padding(CHIP_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
            tint = contentColor,
        )
        Text(
            text = label,
            fontSize = LABEL_SIZE,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}
