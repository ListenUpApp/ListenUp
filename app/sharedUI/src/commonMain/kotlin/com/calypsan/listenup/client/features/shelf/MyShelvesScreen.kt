package com.calypsan.listenup.client.features.shelf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.home.ShelfTile
import com.calypsan.listenup.client.presentation.shelf.MyShelvesUiState
import com.calypsan.listenup.client.presentation.shelf.MyShelvesViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_couldnt_load_shelves
import listenup.composeapp.generated.resources.home_my_shelves
import listenup.composeapp.generated.resources.home_new_shelf
import listenup.composeapp.generated.resources.home_shelves_invitation
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private val ShelfCellMinWidth = 160.dp

/** The list's padding: the grid margin at the sides, a little air above, room below. */
private val MyShelvesContentPadding =
    PaddingValues(start = Spacing.gridMargin, end = Spacing.gridMargin, top = Spacing.sm, bottom = Spacing.xl)

/**
 * The My shelves list (spec §3.2 H24), reached from Home's See all: every shelf the reader owns, as Home's tiles, with
 * New shelf in the top bar.
 */
@Composable
fun MyShelvesScreen(
    onBack: () -> Unit,
    onOpenShelf: (String) -> Unit,
    onCreateShelf: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyShelvesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MyShelvesContent(state, onBack, onOpenShelf, onCreateShelf, modifier)
}

/** The list for a given [state]; stateless, so tests and previews draw it without a ViewModel. */
@Composable
internal fun MyShelvesContent(
    state: MyShelvesUiState,
    onBack: () -> Unit,
    onOpenShelf: (String) -> Unit,
    onCreateShelf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListenUpScaffold(
        topBar = {
            ListenUpTopAppBar(
                title = stringResource(Res.string.home_my_shelves),
                onBack = onBack,
                actions = {
                    IconButton(onClick = onCreateShelf) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.home_new_shelf))
                    }
                },
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues)) {
            when (state) {
                is MyShelvesUiState.Loading -> {
                    ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                // The list is live on Room, so there is no Try again: coming back to the screen reads it afresh.
                is MyShelvesUiState.Error -> {
                    MyShelvesMessage(stringResource(Res.string.home_couldnt_load_shelves))
                }

                is MyShelvesUiState.Ready -> {
                    if (state.shelves.isEmpty()) {
                        MyShelvesMessage(stringResource(Res.string.home_shelves_invitation))
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(ShelfCellMinWidth),
                            contentPadding = MyShelvesContentPadding,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap),
                            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(state.shelves, key = { it.idString }) { shelf ->
                                ShelfTile(
                                    shelf = shelf,
                                    onOpen = { onOpenShelf(shelf.idString) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyShelvesMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.screenMargin, vertical = Spacing.lg),
    )
}
