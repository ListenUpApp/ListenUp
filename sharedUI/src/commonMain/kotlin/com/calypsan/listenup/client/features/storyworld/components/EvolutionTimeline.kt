package com.calypsan.listenup.client.features.storyworld.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.components.AnchorChip
import com.calypsan.listenup.client.presentation.storyworld.EvolutionRow
import com.calypsan.listenup.client.presentation.storyworld.EvolutionUi
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.story_so_far_evolution_caption
import listenup.composeapp.generated.resources.story_so_far_evolution_frontier
import listenup.composeapp.generated.resources.story_so_far_evolution_hidden

private val BULLET_SIZE = 30.dp
private val CONNECTOR_LINE_WIDTH = 2.dp
private val ROW_BOTTOM_SPACING = 20.dp
private val DASH_LINE_HEIGHT = 1.5.dp
private val DASH_PATTERN = floatArrayOf(8f, 6f)

/**
 * The Entity Detail Evolution tab's real timeline: a caption banner, the revealed entries in
 * chronological order (each a bulleted row — the latest bullet emphasized), a frontier divider
 * when [EvolutionUi.hidden] is non-empty, then the hidden rows — existence only, no entry text,
 * no edit/delete affordances (unlike the Entries tab's `EntryTimelineRow`, which this deliberately
 * does not reuse).
 *
 * @param evolution The entity's frontier-divided Evolution state.
 * @param modifier Modifier for the timeline.
 */
@Composable
fun EvolutionTimeline(
    evolution: EvolutionUi,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        EvolutionCaptionBanner()
        Spacer(Modifier.height(20.dp))

        evolution.revealed.forEachIndexed { index, row ->
            EvolutionRevealedRow(row = row, isLast = index == evolution.revealed.lastIndex)
        }

        val frontierLabel = evolution.frontierLabel
        if (evolution.hidden.isNotEmpty() && frontierLabel != null) {
            FrontierDividerRow(label = anchorLabelText(frontierLabel))
            Spacer(Modifier.height(ROW_BOTTOM_SPACING))
        }

        evolution.hidden.forEachIndexed { index, row ->
            EvolutionHiddenRow(row = row, isLast = index == evolution.hidden.lastIndex)
        }
    }
}

/** The "how this story has unfolded" explainer banner atop the Evolution timeline. */
@Composable
private fun EvolutionCaptionBanner() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.story_so_far_evolution_caption),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A revealed Evolution row — a bulleted checkmark (emphasized on [EvolutionRow.isLatest]), text, and anchor. */
@Composable
private fun EvolutionRevealedRow(
    row: EvolutionRow,
    isLast: Boolean,
) {
    val bulletColor =
        if (row.isLatest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val bulletIconTint = if (row.isLatest) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary

    TimelineRow(
        isLast = isLast,
        bullet = {
            Box(
                modifier = Modifier.size(BULLET_SIZE).clip(CircleShape).background(bulletColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = bulletIconTint,
                )
            }
        },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.renderedText.orEmpty(),
                fontSize = 15.5.sp,
                lineHeight = 23.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            AnchorChip(label = anchorLabelText(row.anchor))
        }
    }
}

/** A hidden Evolution row — a locked bullet, italic placeholder text, and anchor. Existence only. */
@Composable
private fun EvolutionHiddenRow(
    row: EvolutionRow,
    isLast: Boolean,
) {
    TimelineRow(
        isLast = isLast,
        bullet = {
            Box(
                modifier =
                    Modifier
                        .size(BULLET_SIZE)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.story_so_far_evolution_hidden),
                fontSize = 15.5.sp,
                lineHeight = 23.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            AnchorChip(label = anchorLabelText(row.anchor), muted = true)
        }
    }
}

/**
 * Shared row shell for [EvolutionRevealedRow]/[EvolutionHiddenRow]: the bullet column (with a
 * connector line to the next row, omitted when [isLast]) followed by the row's own content, laid
 * out as trailing siblings in the same [Row] by the caller (mirrors `EntryTimelineRow`'s shape).
 */
@Composable
private fun TimelineRow(
    isLast: Boolean,
    bullet: @Composable () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(bottom = if (isLast) 0.dp else ROW_BOTTOM_SPACING),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxHeight()) {
            bullet()
            if (!isLast) {
                Box(
                    modifier =
                        Modifier
                            .padding(top = 4.dp)
                            .width(CONNECTOR_LINE_WIDTH)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        content()
    }
}

/** The "your frontier" divider — a primary-tinted bolt, a dashed rule, and the frontier's anchor label. */
@Composable
private fun FrontierDividerRow(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(BULLET_SIZE), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Bolt,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        val dashColor = MaterialTheme.colorScheme.primary
        Canvas(modifier = Modifier.weight(1f).height(DASH_LINE_HEIGHT)) {
            drawLine(
                color = dashColor,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = DASH_LINE_HEIGHT.toPx(),
                pathEffect = PathEffect.dashPathEffect(DASH_PATTERN),
            )
        }
        Text(
            text = stringResource(Res.string.story_so_far_evolution_frontier, label),
            fontSize = 12.sp,
            fontWeight = FontWeight.W800,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
