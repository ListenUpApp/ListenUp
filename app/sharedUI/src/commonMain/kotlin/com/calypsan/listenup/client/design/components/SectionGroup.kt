package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.Spacing

/** The gap between two segments of a grouped list — Material 3 Expressive's segmented-list gap. */
private val SEGMENT_GAP = 2.dp

/** Inner corner of a segment; the group's outer corners come from clipping the whole stack. */
private val SEGMENT_INNER_CORNER = 4.dp

/** Minimum height of a section header, so headers with and without a trailing button line up. */
private val HEADER_MIN_HEIGHT = 40.dp

/** The shape of one segment inside a [SegmentedGroup]: small corners, softened at the ends by the group. */
internal val SegmentShape: Shape = RoundedCornerShape(SEGMENT_INNER_CORNER)

/**
 * The fill of one segment. Segments sit a container level above the page, so the gaps between them
 * read as separation without a hairline.
 */
internal val segmentColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainer

/**
 * The canonical titled group of rows — Material 3 Expressive's segmented list under a sentence-case
 * subheader. The [label] is a `titleSmall` heading in `primary`, as Material's list subheaders are,
 * and the [content] is a [SegmentedGroup]: every row is its own tonal segment, set apart from its
 * neighbours by a small gap rather than an inset hairline, with the group's ends rounded.
 *
 * Settings sections (Appearance, Playback, …), the admin sections (Server, Users, …) and the edit
 * forms that group their fields all compose this.
 *
 * @param label Section heading, shown as written — sentence case, never shouted.
 * @param modifier Modifier for the whole section column.
 * @param trailing Optional content at the end of the header (e.g. an Add button); when null the
 *   header is just the label.
 * @param content The group's segments: a [SettingRow] paints its own; anything else goes in a
 *   [SectionSegment].
 */
@Composable
fun SectionGroup(
    label: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = HEADER_MIN_HEIGHT)
                    .padding(start = Spacing.lg, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .semantics { heading() }
                        .then(if (trailing != null) Modifier.weight(1f) else Modifier),
            )
            trailing?.invoke(this)
        }
        SegmentedGroup(content = content)
    }
}

/**
 * A stack of segments with no header — the body of a [SectionGroup], for a screen that titles the
 * group some other way. Clips the stack to the rounded group shape and spaces the segments by the
 * segmented-list gap; each child paints its own segment ([SettingRow] does, and [SectionSegment]
 * wraps anything else).
 *
 * @param modifier Modifier for the stack.
 * @param content The segments, top to bottom.
 */
@Composable
fun SegmentedGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium),
        verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
        content = content,
    )
}

/**
 * One segment of a [SegmentedGroup] for content that is not a [SettingRow] — a form field, an empty
 * state, a grid. It paints the same tonal fill and shape a row does, so a mixed group still reads as
 * one set of segments.
 *
 * @param modifier Modifier for the segment.
 * @param content The segment's content.
 */
@Composable
fun SectionSegment(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().clip(SegmentShape).background(segmentColor),
        content = content,
    )
}
