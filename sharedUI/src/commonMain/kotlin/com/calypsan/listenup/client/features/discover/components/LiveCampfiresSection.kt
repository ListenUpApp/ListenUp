package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.discover.DiscoverViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.campfire_live_now
import listenup.composeapp.generated.resources.campfire_listening_now_count
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Discover's "Live now" row (co-listening design spec §7, campfire implementation plan Task 10) —
 * open Campfire sessions the caller may currently discover. Renders nothing while empty (the same
 * "no section" idiom as [CurrentlyListeningSection]). Tapping a card opens the book's detail page,
 * where the live 🔥 badge offers the actual join flow — this row is discovery, not the join
 * surface itself.
 */
@Composable
fun LiveCampfiresSection(
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = koinViewModel(),
) {
    val liveCampfires by viewModel.liveCampfiresUiState.collectAsStateWithLifecycle()
    if (liveCampfires.isEmpty()) return

    Column(modifier = modifier) {
        SectionTitle(
            title = stringResource(Res.string.campfire_live_now),
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        BrowseCarousel(items = liveCampfires, key = { it.summary.id.value }) { entry ->
            BookCard(
                cover = entry.book.toCoverModel(),
                onClick = { onBookClick(entry.book.id.value) },
                subtitle = stringResource(Res.string.campfire_listening_now_count, entry.summary.memberCount),
                cardWidth = 140.dp,
            )
        }
    }
}
