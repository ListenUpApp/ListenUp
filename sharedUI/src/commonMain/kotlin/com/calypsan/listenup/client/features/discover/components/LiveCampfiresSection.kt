package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.campfire.CampfirePhase
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.campfire.CampfireViewModel
import com.calypsan.listenup.client.presentation.discover.DiscoverViewModel
import com.calypsan.listenup.client.presentation.discover.LiveCampfireUiModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.campfire_campfires_title
import listenup.composeapp.generated.resources.campfire_discover_gathering_listening_count
import listenup.composeapp.generated.resources.campfire_discover_live_listening_count
import listenup.composeapp.generated.resources.campfire_discover_start_a_campfire
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private val TileWidth: Dp = 140.dp

/**
 * Discover's "Campfires" row (co-listening design spec §7; reworked for discoverability — the
 * create entry point used to live only as a floating icon on the book-detail screen, covering
 * that screen's own overflow menu). Always rendered (unlike the other Discover sections, which
 * hide themselves when empty) so starting a session is never buried behind a book's menu.
 *
 * The first tile always offers [StartCampfireTile] (opens [CampfireBookPickerSheet]); any open
 * sessions the caller may join follow. Tapping a live session joins it directly via the shared,
 * app-hoisted [campfireViewModel] — the same instance [com.calypsan.listenup.client.features.nowplaying.NowPlayingHost]
 * observes, so the full-screen lobby/room overlay appears immediately, with no intermediate
 * navigation to the book's detail page.
 *
 * @param campfireViewModel The app-hoisted Campfire session ViewModel (see `AuthenticatedNavigation`'s
 * KDoc for why one instance is shared across every screen that can join or host a session).
 * @param onStartCampfire Called with the picked book's id once the user chooses one from
 * [CampfireBookPickerSheet] — the caller routes to that book's detail screen with the create flow
 * pre-armed (there is no dedicated create surface on Discover itself).
 */
@Composable
fun LiveCampfiresSection(
    campfireViewModel: CampfireViewModel,
    onStartCampfire: (bookId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = koinViewModel(),
) {
    val liveCampfires by viewModel.liveCampfiresUiState.collectAsStateWithLifecycle()
    var showBookPicker by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        SectionTitle(
            title = stringResource(Res.string.campfire_campfires_title),
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        val rowItems: List<CampfireRowItem> =
            listOf(CampfireRowItem.Start) + liveCampfires.map { CampfireRowItem.Live(it) }

        BrowseCarousel(
            items = rowItems,
            itemWidth = TileWidth,
            key = { item ->
                when (item) {
                    CampfireRowItem.Start -> "start_campfire"
                    is CampfireRowItem.Live -> item.entry.summary.id.value
                }
            },
        ) { item ->
            when (item) {
                CampfireRowItem.Start -> {
                    StartCampfireTile(onClick = { showBookPicker = true })
                }

                is CampfireRowItem.Live -> {
                    LiveCampfireCard(entry = item.entry, onClick = { campfireViewModel.join(item.entry.summary.id) })
                }
            }
        }
    }

    if (showBookPicker) {
        CampfireBookPickerSheet(
            onBookSelected = { bookId ->
                showBookPicker = false
                onStartCampfire(bookId)
            },
            onDismiss = { showBookPicker = false },
        )
    }
}

/** One entry in the Campfires carousel — the always-present start tile, or a joinable session. */
private sealed interface CampfireRowItem {
    /** The always-first tile that opens the book picker. */
    data object Start : CampfireRowItem

    /** An open session the caller may join. */
    data class Live(
        val entry: LiveCampfireUiModel,
    ) : CampfireRowItem
}

/** A joinable open Campfire session — book cover, phase + listener-count subtitle. */
@Composable
private fun LiveCampfireCard(
    entry: LiveCampfireUiModel,
    onClick: () -> Unit,
) {
    val subtitle =
        if (entry.summary.phase == CampfirePhase.LIVE) {
            stringResource(Res.string.campfire_discover_live_listening_count, entry.summary.memberCount)
        } else {
            stringResource(Res.string.campfire_discover_gathering_listening_count, entry.summary.memberCount)
        }

    BookCard(
        cover = entry.book.toCoverModel(),
        onClick = onClick,
        subtitle = subtitle,
        cardWidth = TileWidth,
    )
}

/**
 * The Campfires row's always-present first tile — opens [CampfireBookPickerSheet] to pick a book
 * and start a new session. Sized and laid out to match [BookCard] (cover-sized square + a label
 * beneath) so it reads as one more card in the row rather than an outlier.
 */
@Composable
private fun StartCampfireTile(onClick: () -> Unit) {
    Column(modifier = Modifier.width(TileWidth).clickable(onClick = onClick)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(Res.string.campfire_discover_start_a_campfire),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}
