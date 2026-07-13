package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_more_options
import listenup.composeapp.generated.resources.common_back

/**
 * Plain (non-collapsing) top app bar for the Book Detail screen.
 *
 * Shows a back arrow, the screen label ("Book details"), and a three-dot overflow that delegates
 * to [BookActionsMenu]. Container colour is [MaterialTheme.colorScheme.surface] so it blends
 * seamlessly with the hero section below it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // Compose state-hoisting over BookActionsMenu requires one callback per action item
@Composable
fun BookDetailTopBar(
    title: String,
    isComplete: Boolean,
    hasProgress: Boolean,
    isAdmin: Boolean,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onEditChaptersClick: () -> Unit,
    onFindMetadataClick: () -> Unit,
    onMarkCompleteClick: () -> Unit,
    onMarkNotStartedClick: () -> Unit,
    onAddToShelfClick: () -> Unit,
    onAddToCollectionClick: () -> Unit,
    onShareClick: () -> Unit,
    campfireLiveCount: Int,
    onCampfireClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(text = title) },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                )
            }
        },
        actions = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(Res.string.book_detail_more_options),
                    )
                }
                BookActionsMenu(
                    expanded = showMenu,
                    onDismiss = { showMenu = false },
                    isComplete = isComplete,
                    hasProgress = hasProgress,
                    isAdmin = isAdmin,
                    onEditClick = {
                        showMenu = false
                        onEditClick()
                    },
                    onEditChaptersClick = {
                        showMenu = false
                        onEditChaptersClick()
                    },
                    onFindMetadataClick = {
                        showMenu = false
                        onFindMetadataClick()
                    },
                    onMarkCompleteClick = {
                        showMenu = false
                        onMarkCompleteClick()
                    },
                    onMarkNotStartedClick = {
                        showMenu = false
                        onMarkNotStartedClick()
                    },
                    onAddToShelfClick = {
                        showMenu = false
                        onAddToShelfClick()
                    },
                    onAddToCollectionClick = {
                        showMenu = false
                        onAddToCollectionClick()
                    },
                    onShareClick = {
                        showMenu = false
                        onShareClick()
                    },
                    campfireLiveCount = campfireLiveCount,
                    onCampfireClick = {
                        showMenu = false
                        onCampfireClick()
                    },
                    onDeleteClick = {
                        showMenu = false
                        onDeleteClick()
                    },
                )
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        modifier = modifier,
    )
}
