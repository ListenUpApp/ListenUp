package com.calypsan.listenup.client.features.readingorder.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.components.entityTint
import com.calypsan.listenup.client.domain.model.ReadingOrder
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.reading_orders_active_chip
import listenup.composeapp.generated.resources.reading_orders_book_count_one
import listenup.composeapp.generated.resources.reading_orders_books_count
import listenup.composeapp.generated.resources.reading_orders_privacy_private
import listenup.composeapp.generated.resources.reading_orders_privacy_visible

private const val MAX_SPINES = 4
private const val SPINE_ASPECT = 37f / 50f
private val SPINE_DEFAULT_HEIGHT = 50.dp
private val SPINE_OVERLAP = 9.dp
private val SPINE_CORNER = 8.dp

/**
 * A reading-order row: the spine stack, name + active chip, subtitle, and (on owned rows) a
 * privacy label, ending in a chevron. Shared by the owned and discovered sections of
 * [com.calypsan.listenup.client.features.readingorder.ReadingOrdersScreen].
 *
 * @param order The reading order to render.
 * @param isActive Whether [order] is the caller's active reading order for the loaded series.
 * @param subtitle Pre-resolved subtitle line — book count for owned rows, attribution/owner for
 *   discovered rows (the caller resolves this since the two sections read different fields).
 * @param onClick Navigates to the order's detail screen.
 * @param showPrivacy Shows the [PrivacyLabel] below the subtitle — owned rows only; a discovered
 *   order is by definition visible to the viewer, so the label would be redundant there.
 */
@Composable
fun OrderCard(
    order: ReadingOrder,
    isActive: Boolean,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showPrivacy: Boolean = false,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OrderSpines(seed = order.idString, count = order.bookCount)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = order.name,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isActive) ActiveChip()
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showPrivacy) {
                PrivacyLabel(isPrivate = order.isPrivate, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Up to [MAX_SPINES] offset, tinted "book spine" rectangles standing in for a reading order's
 * cover deck — cheaper than resolving real covers for a list row, and stable per-order via
 * [entityTint] (seeded with the order id and spine index).
 */
@Composable
internal fun OrderSpines(
    seed: String,
    count: Int,
    modifier: Modifier = Modifier,
    height: Dp = SPINE_DEFAULT_HEIGHT,
) {
    val spineCount = count.coerceIn(1, MAX_SPINES)
    val width = height * SPINE_ASPECT
    Box(modifier = modifier.height(height).width(width + SPINE_OVERLAP * (spineCount - 1))) {
        repeat(spineCount) { index ->
            Box(
                modifier =
                    Modifier
                        .offset(x = SPINE_OVERLAP * index)
                        .size(width = width, height = height)
                        .clip(RoundedCornerShape(SPINE_CORNER))
                        .background(entityTint("$seed-$index")),
            )
        }
    }
}

/** Small primary pill marking the row as the caller's active (spoiler-clock) reading order. */
@Composable
internal fun ActiveChip(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(
                text = stringResource(Res.string.reading_orders_active_chip),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Lock/public icon + "Private"/"Visible" label for a reading order's [ReadingOrder.isPrivate]. */
@Composable
internal fun PrivacyLabel(
    isPrivate: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (isPrivate) Icons.Filled.Lock else Icons.Filled.Public,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = tint,
        )
        Text(
            text =
                stringResource(
                    if (isPrivate) Res.string.reading_orders_privacy_private else Res.string.reading_orders_privacy_visible,
                ),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

/** `"1 book"` / `"12 books"` — the reading-order-scoped singular/plural pair (see [ReadingOrder.bookCount]). */
@Composable
internal fun booksCountText(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.reading_orders_book_count_one, count)
    } else {
        stringResource(Res.string.reading_orders_books_count, count)
    }
