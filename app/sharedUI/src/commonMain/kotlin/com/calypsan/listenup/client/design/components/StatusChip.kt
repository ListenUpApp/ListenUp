package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A status, drawn in an outlined assist chip's shape but never a control (m3.material.io/components/chips: the
 * assist chip's 8dp shape and outline). Announced politely when it changes; it carries no click semantics, so
 * TalkBack never offers it as a button.
 */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurfaceVariant
    val outline = colors.outlineVariant
    Box(
        modifier =
            modifier
                .heightIn(min = 32.dp)
                .border(width = 1.dp, color = outline, shape = MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 12.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
