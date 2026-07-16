package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.calypsan.listenup.api.sync.EntityKind

private const val DEFAULT_RING_PADDING = 2.5f

/**
 * One entity rendered by an [AvatarStack]: the display name, [EntityKind], and tint seed an
 * [EntityTile] needs to render itself.
 *
 * @param name Entity display name.
 * @param kind Which Story World taxonomy the entity belongs to.
 * @param tintSeed Stable seed (typically the entity id) feeding [entityTint].
 */
data class AvatarStackEntry(
    val name: String,
    val kind: EntityKind,
    val tintSeed: String,
)

/**
 * An overlapping row of [EntityTile]s — the "who's in this scene" glyph cluster shown on Story
 * World log entries. Entries render left-to-right with each one overlapping the previous by
 * [overlap]; earlier entries draw on top of later ones so the reading order matches the visual
 * stacking order. Each tile sits inside a [MaterialTheme.colorScheme.surfaceContainerLow] ring so
 * overlapping tiles stay visually separated — the ring is always circular, even for
 * [EntityKind.LOCATION]/[EntityKind.ITEM] tiles, to keep the stack's silhouette uniform. Only the
 * first [maxVisible] entries render; the rest are dropped with no overflow indicator.
 *
 * @param entries The entities to render, most-prominent first.
 * @param size Edge length of each [EntityTile].
 * @param modifier Modifier for the row.
 * @param overlap How far each subsequent tile's ring overlaps the previous one.
 * @param maxVisible Maximum number of entries rendered.
 */
@Composable
fun AvatarStack(
    entries: List<AvatarStackEntry>,
    size: Dp,
    modifier: Modifier = Modifier,
    overlap: Dp = 11.dp,
    maxVisible: Int = 3,
) {
    val visible = entries.take(maxVisible)
    Row(modifier = modifier) {
        visible.forEachIndexed { index, entry ->
            Box(
                modifier =
                    Modifier
                        .zIndex((visible.size - index).toFloat())
                        .offset(x = -overlap * index)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(DEFAULT_RING_PADDING.dp),
            ) {
                EntityTile(
                    name = entry.name,
                    kind = entry.kind,
                    tintSeed = entry.tintSeed,
                    size = size,
                )
            }
        }
    }
}
