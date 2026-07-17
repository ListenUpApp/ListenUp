package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.AvatarStackEntry
import com.calypsan.listenup.client.design.components.EntityTile
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.features.nowplaying.components.PlayerPanelScaffold
import com.calypsan.listenup.client.features.storyworld.components.SceneStack
import com.calypsan.listenup.client.presentation.storyworld.EntityCard
import com.calypsan.listenup.client.presentation.storyworld.StandRowUi
import com.calypsan.listenup.client.presentation.storyworld.StorySoFarUiState
import com.calypsan.listenup.client.presentation.storyworld.StorySoFarViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.story_so_far_empty_cta
import listenup.composeapp.generated.resources.story_so_far_empty_title
import listenup.composeapp.generated.resources.story_so_far_peek_expand
import listenup.composeapp.generated.resources.story_so_far_scene_summary
import listenup.composeapp.generated.resources.story_so_far_scene_summary_short
import listenup.composeapp.generated.resources.story_so_far_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private val PEEK_TILE_SIZE = 40.dp
private val PEEK_ICON_SIZE = 20.dp
private val PEEK_ROW_SHAPE = RoundedCornerShape(16.dp)
private val PEEK_LOADING_VERTICAL_PADDING = 24.dp
private const val PEEK_SCENE_SUMMARY_MAX_NAMES = 2

/**
 * The Now Playing "Story So Far" peek — a compact panel reachable from
 * [com.calypsan.listenup.client.features.nowplaying.components.PlayerSecondaryActions], showing a
 * quick spoiler-safe glance at the playing book's Story World (who's in scene, where the top
 * entity stands) with an [onExpand] affordance to the full
 * [com.calypsan.listenup.client.features.storyworld.StorySoFarScreen]. The panel never dead-ends:
 * even with nothing folded in yet, [onOpenHub] offers a way into the Story World hub.
 *
 * @param bookId The currently-playing book to recap.
 * @param onExpand Called when the viewer asks to see the full Story So Far screen for [bookId].
 * @param onOpenHub Called with `(seriesId, bookId)` — mirrors
 *   [com.calypsan.listenup.client.presentation.storyworld.WorldRef] — to open the Story World hub,
 *   e.g. from the empty floor's call to action.
 * @param onDismissRequest Called when the panel is dismissed (scrim tap, back, drag-down, close).
 */
@Composable
fun StorySoFarPanel(
    bookId: String,
    onExpand: () -> Unit,
    onOpenHub: (seriesId: String?, bookId: String?) -> Unit,
    onDismissRequest: () -> Unit,
    viewModel: StorySoFarViewModel = koinViewModel(),
) {
    LaunchedEffect(bookId) { viewModel.load(bookId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    PlayerPanelScaffold(title = "", onDismiss = onDismissRequest) {
        PeekHeader(onExpand = onExpand)
        Spacer(Modifier.height(16.dp))
        when (val current = state) {
            StorySoFarUiState.Idle, StorySoFarUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = PEEK_LOADING_VERTICAL_PADDING),
                    contentAlignment = Alignment.Center,
                ) {
                    ListenUpLoadingIndicator()
                }
            }

            is StorySoFarUiState.EmptyFloor -> {
                PeekEmptyFloor(state = current, onOpenHub = onOpenHub)
            }

            is StorySoFarUiState.Ready -> {
                PeekReadyContent(state = current)
            }
        }
    }
}

/** The panel's own header row — Story So Far identity + the "Expand" affordance to the full screen. */
@Composable
private fun PeekHeader(onExpand: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Public,
            contentDescription = null,
            modifier = Modifier.size(PEEK_ICON_SIZE),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(Res.string.story_so_far_title),
            fontSize = 19.sp,
            fontWeight = FontWeight.W800,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onExpand) {
            Text(stringResource(Res.string.story_so_far_peek_expand))
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ExpandLess,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The empty-floor peek — a quiet placeholder with a tonal way into the full Story World hub. */
@Composable
private fun PeekEmptyFloor(
    state: StorySoFarUiState.EmptyFloor,
    onOpenHub: (seriesId: String?, bookId: String?) -> Unit,
) {
    val seriesId = state.seriesId
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.story_so_far_empty_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        FilledTonalButton(
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            onClick = {
                val targetBookId = if (seriesId == null) state.bookId else null
                onOpenHub(seriesId, targetBookId)
            },
        ) {
            Icon(imageVector = Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.story_so_far_empty_cta))
        }
    }
}

/** The ready peek — who's in scene, then the top "where things stand" row. */
@Composable
private fun PeekReadyContent(state: StorySoFarUiState.Ready) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.inScene.isNotEmpty()) {
            SceneStack(
                entries = state.inScene.map { AvatarStackEntry(name = it.name, kind = it.kind, tintSeed = it.id) },
                summaryText = peekSceneSummaryText(state.inScene),
            )
        }
        state.standRows.firstOrNull()?.let { row -> PeekStandRow(row = row) }
    }
}

/**
 * Already-localized "who's present" caption for the peek's [SceneStack] — mirrors
 * `StorySoFarScreen`'s own `sceneSummaryText`, duplicated rather than shared per this feature's
 * established small-visual-duplication convention (see `StoryWorldSection`'s KDoc).
 */
@Composable
private fun peekSceneSummaryText(entities: List<EntityCard>): String {
    val firstNames = entities.map { it.name.substringBefore(' ') }
    return when {
        entities.isEmpty() -> {
            ""
        }

        entities.size == 1 -> {
            stringResource(Res.string.story_so_far_scene_summary_short, firstNames[0])
        }

        entities.size == PEEK_SCENE_SUMMARY_MAX_NAMES -> {
            firstNames.joinToString(", ")
        }

        else -> {
            val leading = firstNames.take(PEEK_SCENE_SUMMARY_MAX_NAMES).joinToString(", ")
            stringResource(
                Res.string.story_so_far_scene_summary,
                leading,
                entities.size - PEEK_SCENE_SUMMARY_MAX_NAMES,
            )
        }
    }
}

/** A single compact "where things stand" row — a leading [EntityTile], the name, and a status line. */
@Composable
private fun PeekStandRow(row: StandRowUi) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PEEK_ROW_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EntityTile(name = row.entity.name, kind = row.entity.kind, tintSeed = row.entity.id, size = PEEK_TILE_SIZE)
            Column {
                Text(
                    text = row.entity.name,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                row.statusLine?.let { statusLine ->
                    Text(
                        text = statusLine,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
