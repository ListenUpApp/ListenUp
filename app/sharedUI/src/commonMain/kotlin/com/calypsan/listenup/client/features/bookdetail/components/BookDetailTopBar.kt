package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.design.LocalInDetailPane
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_close
import listenup.composeapp.generated.resources.book_detail_more_options

/**
 * Plain (non-collapsing) top app bar for the Book Detail screen.
 *
 * Shows a back arrow, the screen label ("Book details"), and a three-dot overflow that delegates
 * to [BookActionsMenu]. Beside a list (see [LocalInDetailPane]) the back arrow becomes a Close. Container colour is [MaterialTheme.colorScheme.surface] so it blends
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
    onFindMetadataClick: () -> Unit,
    onEditChaptersClick: () -> Unit,
    onMarkCompleteClick: () -> Unit,
    onMarkNotStartedClick: () -> Unit,
    onRestartClick: () -> Unit,
    onAddToShelfClick: () -> Unit,
    onAddToCollectionClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = true,
) {
    val haptics = LocalHaptics.current
    var showMenu by remember { mutableStateOf(false) }
    val inDetailPane = LocalInDetailPane.current

    ListenUpTopAppBar(
        title = title,
        onBack = onBackClick,
        // Beside its list, Back no longer leaves anything — it closes this pane.
        navigationIcon = if (inDetailPane) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
        navigationContentDescription = if (inDetailPane) stringResource(Res.string.book_detail_close) else null,
        actions = {
            Box {
                IconButton(
                    onClick = {
                        haptics.press()
                        showMenu = true
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(Res.string.book_detail_more_options),
                    )
                }
                BookActionsMenu(
                    expanded = showMenu,
                    onDismiss = { showMenu = false },
                    isComplete = isComplete,
                    hasProgress = hasProgress,
                    isAdmin = isAdmin,
                    actionsEnabled = actionsEnabled,
                    onEditClick = {
                        showMenu = false
                        onEditClick()
                    },
                    onFindMetadataClick = {
                        showMenu = false
                        onFindMetadataClick()
                    },
                    onEditChaptersClick = {
                        showMenu = false
                        onEditChaptersClick()
                    },
                    onMarkCompleteClick = {
                        showMenu = false
                        onMarkCompleteClick()
                    },
                    onMarkNotStartedClick = {
                        showMenu = false
                        onMarkNotStartedClick()
                    },
                    onRestartClick = {
                        showMenu = false
                        onRestartClick()
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
