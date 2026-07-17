package com.calypsan.listenup.client.features.storyworld.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.client.design.components.EntityTile

private val TILE_SIZE = 44.dp
private val NEW_BADGE_HEIGHT = 20.dp
private val LOCATION_PILL_HEIGHT = 22.dp
private val LOCATION_ICON_SIZE = 13.dp
private val STATUS_LINE_SIZE = 13.5.sp
private val STATUS_LINE_LINE_HEIGHT = 18.9.sp
private const val NEW_ROW_TINT_ALPHA = 0.08f

/**
 * A "where things stand" row in a Story So Far list: a leading [EntityTile], the entity's name
 * (with an optional NEW pill), an optional location pill, an optional status line, and a trailing
 * chevron. The whole row is clickable via [onClick]. When [isNew] is set, the row's background is
 * tinted with a faint primary wash so newly-surfaced entities stand out in the list.
 *
 * @param name Entity display name.
 * @param kind Which Story World taxonomy the entity belongs to; drives the leading [EntityTile].
 * @param tintSeed Stable seed (typically the entity id) feeding the tile's tint.
 * @param locationLabel Already-localized "where they are" pill text; omitted when null.
 * @param statusLine Already-localized status line shown under the pill; omitted when null.
 * @param isNew Whether this entity is newly surfaced as of the current frontier.
 * @param newBadgeText Already-localized text for the NEW pill (only shown when [isNew]).
 * @param onClick Called when the row is tapped.
 * @param modifier Modifier for the row.
 */
@Composable
fun StandRow(
    name: String,
    kind: EntityKind,
    tintSeed: String,
    locationLabel: String?,
    statusLine: String?,
    isNew: Boolean,
    newBadgeText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowBackground =
        if (isNew) MaterialTheme.colorScheme.primary.copy(alpha = NEW_ROW_TINT_ALPHA) else Color.Transparent

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(rowBackground)
                .clickable(onClick = onClick)
                .padding(vertical = 13.dp, horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        EntityTile(name = name, kind = kind, tintSeed = tintSeed, size = TILE_SIZE)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (isNew) {
                    NewBadge(text = newBadgeText)
                }
            }
            locationLabel?.let { LocationPill(label = it) }
            statusLine?.let {
                Text(
                    text = it,
                    fontSize = STATUS_LINE_SIZE,
                    lineHeight = STATUS_LINE_LINE_HEIGHT,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The "NEW" pill shown next to a [StandRow]'s name when the entity is newly surfaced. */
@Composable
private fun NewBadge(text: String) {
    Box(
        modifier =
            Modifier
                .height(NEW_BADGE_HEIGHT)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** The "where they are" location pill shown under a [StandRow]'s name. */
@Composable
private fun LocationPill(label: String) {
    Row(
        modifier =
            Modifier
                .height(LOCATION_PILL_HEIGHT)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Place,
            contentDescription = null,
            modifier = Modifier.size(LOCATION_ICON_SIZE),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
