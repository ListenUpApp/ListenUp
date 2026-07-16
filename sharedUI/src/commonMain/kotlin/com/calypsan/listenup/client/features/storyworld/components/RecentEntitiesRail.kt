package com.calypsan.listenup.client.features.storyworld.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.components.EntityTile
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.presentation.storyworld.EntityCard
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.story_world_recently_updated

private val TILE_SIZE = 60.dp
private val NAME_FONT_SIZE = 12.5.sp

/**
 * "Recently updated" horizontal rail — the entities first mentioned by the world's most recent
 * visible log entries, most-recent-first. Hidden entirely by the caller when [entities] is empty.
 */
@Composable
fun RecentEntitiesRail(
    entities: List<EntityCard>,
    onEntityClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(title = stringResource(Res.string.story_world_recently_updated))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(entities, key = { it.id }) { entity ->
                RecentEntityItem(entity = entity, onClick = { onEntityClick(entity.id) })
            }
        }
    }
}

@Composable
private fun RecentEntityItem(
    entity: EntityCard,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(TILE_SIZE + 8.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EntityTile(name = entity.name, kind = entity.kind, tintSeed = entity.id, size = TILE_SIZE)
        Text(
            text = entity.name,
            fontSize = NAME_FONT_SIZE,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
