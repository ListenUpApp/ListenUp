package com.calypsan.listenup.client.features.storyworld.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.EntityTile
import com.calypsan.listenup.client.presentation.storyworld.EntityCard
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.story_world_quick_create_subtitle
import listenup.composeapp.generated.resources.story_world_quick_create_title

private val ROW_TILE_SIZE = 34.dp
private val VERB_GLYPH_SIZE = 34.dp
private val QUICK_CREATE_TILE_SIZE = 42.dp
private val MAX_VISIBLE_HEIGHT = 176.dp

/**
 * The composer's inline suggestion list — rendered directly under [MentionTextField] as a plain
 * sheet-scoped column (not a floating `Popup`), matching the sheet's own scroll ergonomics rather
 * than fighting them. Shows nothing when there is nothing to suggest.
 *
 * Order: the quick-create row first (when [showQuickCreate]), then entity mention suggestions,
 * then verb-phrase suggestions — a given open trigger only ever populates one of the two
 * suggestion lists, so in practice at most one section renders alongside quick-create.
 *
 * @param suggestions Entity-mention matches for an open `@`/`[` trigger.
 * @param verbSuggestions Verb-phrase matches for an open `*` trigger.
 * @param showQuickCreate Whether to offer "create a new entity named …" atop the list.
 * @param quickCreateQuery The in-progress mention query the quick-create row is offering to create.
 * @param onMentionSelected Called when an entity suggestion is tapped.
 * @param onVerbSelected Called when a verb-phrase suggestion is tapped.
 * @param onQuickCreateClick Called when the quick-create row is tapped.
 * @param modifier Modifier for the popup's container.
 */
@Composable
fun SuggestionPopup(
    suggestions: List<EntityCard>,
    verbSuggestions: List<String>,
    showQuickCreate: Boolean,
    quickCreateQuery: String,
    onMentionSelected: (EntityCard) -> Unit,
    onVerbSelected: (String) -> Unit,
    onQuickCreateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty() && verbSuggestions.isEmpty() && !showQuickCreate) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.heightIn(max = MAX_VISIBLE_HEIGHT).verticalScroll(rememberScrollState())) {
            if (showQuickCreate) {
                QuickCreateRow(query = quickCreateQuery, onClick = onQuickCreateClick)
            }
            suggestions.forEach { entity ->
                MentionSuggestionRow(entity = entity, onClick = { onMentionSelected(entity) })
            }
            verbSuggestions.forEach { phrase ->
                VerbSuggestionRow(phrase = phrase, onClick = { onVerbSelected(phrase) })
            }
        }
    }
}

@Composable
private fun QuickCreateRow(
    query: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(QUICK_CREATE_TILE_SIZE)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.story_world_quick_create_title, query),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(Res.string.story_world_quick_create_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MentionSuggestionRow(
    entity: EntityCard,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EntityTile(name = entity.name, kind = entity.kind, tintSeed = entity.id, size = ROW_TILE_SIZE)
        Text(
            text = entity.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun VerbSuggestionRow(
    phrase: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(VERB_GLYPH_SIZE)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "*",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = phrase,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
