package com.calypsan.listenup.client.features.storyworld.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CAPTION_ICON_SIZE = 15.dp
private val CAPTION_TEXT_SIZE = 12.5.sp
private val CAPTION_LINE_HEIGHT = 17.5.sp
private const val SEGMENT_COUNT = 2

/**
 * The Story So Far "Safe" / "As of here" spoiler-horizon toggle: a two-segment
 * [SingleChoiceSegmentedButtonRow] plus a caption line describing what the current mode shows.
 * "Safe" folds the world up to the reader's frontier only; "As of here" widens the horizon to the
 * viewer's current playback position for this session. Both segments are disabled when [enabled]
 * is false (e.g. while the frontier itself is loading).
 *
 * @param asOfHere True when "As of here" is the selected mode; false selects "Safe".
 * @param safeLabel Already-localized label for the "Safe" segment.
 * @param hereLabel Already-localized label for the "As of here" segment.
 * @param caption Already-localized caption describing the current mode.
 * @param enabled Whether the toggle can be interacted with.
 * @param onChange Called with the new [asOfHere] value when the viewer picks a segment.
 * @param modifier Modifier for the column.
 */
@Composable
fun SafeHereToggle(
    asOfHere: Boolean,
    safeLabel: String,
    hereLabel: String,
    caption: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !asOfHere,
                onClick = { onChange(false) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = SEGMENT_COUNT),
            ) {
                Text(safeLabel)
            }
            SegmentedButton(
                selected = asOfHere,
                onClick = { onChange(true) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = SEGMENT_COUNT),
            ) {
                Text(hereLabel)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (asOfHere) Icons.Outlined.Bolt else Icons.Outlined.Shield,
                contentDescription = null,
                modifier = Modifier.size(CAPTION_ICON_SIZE),
                tint = if (asOfHere) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = caption,
                fontSize = CAPTION_TEXT_SIZE,
                lineHeight = CAPTION_LINE_HEIGHT,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
