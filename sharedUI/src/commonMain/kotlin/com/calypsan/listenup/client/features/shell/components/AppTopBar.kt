package com.calypsan.listenup.client.features.shell.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.UserAvatarMenu
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_search
import listenup.composeapp.generated.resources.shell_close_search
import listenup.composeapp.generated.resources.shell_search_audiobooks
import listenup.composeapp.generated.resources.shell_sync_details_action
import listenup.composeapp.generated.resources.shell_sync_error
import org.jetbrains.compose.resources.stringResource

/**
 * Top app bar for the main shell with collapsible search.
 *
 * Collapsed state: Title, search icon, sync indicator, avatar
 * Expanded state: Back arrow, search field, avatar
 *
 * @param currentDestination Current shell destination (for title)
 * @param syncState Current sync status
 * @param user Current user entity
 * @param isSearchExpanded Whether search is expanded
 * @param searchQuery Current search query
 * @param onSearchExpandedChange Callback when search expand state changes
 * @param onSearchQueryChange Callback when search query changes
 * @param isAvatarMenuExpanded Whether avatar dropdown is expanded
 * @param onAvatarMenuExpandedChange Callback when avatar menu expand state changes
 * @param onAdminClick Callback when administration is clicked (only shown for admin users)
 * @param onSettingsClick Callback when settings is clicked
 * @param onSignOutClick Callback when sign out is clicked
 * @param isSyncDetailsExpanded Whether the sync details dropdown is expanded
 * @param syncIndicatorUiState UI state for the sync details dropdown content
 * @param onRetryOperation Callback when a failed operation retry is clicked
 * @param onDismissOperation Callback when a failed operation dismiss is clicked
 * @param onRetryAll Callback when retry all is clicked
 * @param onDismissAll Callback when dismiss all is clicked
 * @param scrollBehavior Scroll behavior for collapsing on scroll
 */
@Suppress("LongParameterList")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    currentDestination: ShellDestination?,
    syncState: SyncState,
    user: User?,
    isSearchExpanded: Boolean,
    searchQuery: String,
    onSearchExpandedChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    isAvatarMenuExpanded: Boolean,
    onAvatarMenuExpandedChange: (Boolean) -> Unit,
    onAdminClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onMyProfileClick: () -> Unit,
    onSyncIndicatorClick: () -> Unit = {},
    isSyncDetailsExpanded: Boolean = false,
    syncIndicatorUiState: SyncIndicatorUiState? = null,
    onRetryOperation: (String) -> Unit = {},
    onDismissOperation: (String) -> Unit = {},
    onRetryAll: () -> Unit = {},
    onDismissAll: () -> Unit = {},
    onSyncDetailsDismiss: () -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    showAvatar: Boolean = true,
    showAvatarLabel: Boolean = false,
) {
    // Guard against null destination during recomposition transitions
    val safeDestination = currentDestination ?: ShellDestination.Home

    TopAppBar(
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
        navigationIcon = {
            if (isSearchExpanded) {
                IconButton(onClick = { onSearchExpandedChange(false) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.shell_close_search),
                    )
                }
            }
        },
        title = {
            AnimatedContent(
                targetState = isSearchExpanded,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "search_animation",
            ) { expanded ->
                if (expanded) {
                    SearchField(
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        onClose = { onSearchExpandedChange(false) },
                    )
                } else {
                    Text(safeDestination.title)
                }
            }
        },
        actions = {
            // Search icon (hidden when expanded)
            if (!isSearchExpanded) {
                IconButton(onClick = { onSearchExpandedChange(true) }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(Res.string.common_search),
                    )
                }
            }

            // Sync indicator with dropdown (hidden when search expanded)
            if (!isSearchExpanded) {
                SyncIndicator(
                    syncState = syncState,
                    onClick = onSyncIndicatorClick,
                    isSyncDetailsExpanded = isSyncDetailsExpanded,
                    syncIndicatorUiState = syncIndicatorUiState,
                    onRetryOperation = onRetryOperation,
                    onDismissOperation = onDismissOperation,
                    onRetryAll = onRetryAll,
                    onDismissAll = onDismissAll,
                    onSyncDetailsDismiss = onSyncDetailsDismiss,
                )
            }

            // Avatar (hidden on medium/expanded screens where it's in the rail/drawer)
            if (showAvatar) {
                UserAvatarMenu(
                    user = user,
                    expanded = isAvatarMenuExpanded,
                    onExpandedChange = onAvatarMenuExpandedChange,
                    onMyProfileClick = onMyProfileClick,
                    onAdminClick = onAdminClick,
                    onSettingsClick = onSettingsClick,
                    onSignOutClick = onSignOutClick,
                    modifier = Modifier.padding(end = 8.dp),
                    showLabel = showAvatarLabel,
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

/**
 * Search text field for the expanded search state.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(Res.string.shell_search_audiobooks)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions =
            KeyboardActions(
                onSearch = { keyboardController?.hide() },
            ),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
    )
}

/**
 * Sync status indicator with anchored dropdown.
 *
 * Shows:
 * - Spinner during Syncing/Progress/Retrying
 * - Error icon on Error
 * - Nothing for Idle/Success
 *
 * Tappable to show sync details dropdown.
 */
@Suppress("LongParameterList")
@Composable
private fun SyncIndicator(
    syncState: SyncState,
    onClick: () -> Unit,
    isSyncDetailsExpanded: Boolean = false,
    syncIndicatorUiState: SyncIndicatorUiState? = null,
    onRetryOperation: (String) -> Unit = {},
    onDismissOperation: (String) -> Unit = {},
    onRetryAll: () -> Unit = {},
    onDismissAll: () -> Unit = {},
    onSyncDetailsDismiss: () -> Unit = {},
) {
    Box {
        when (syncState) {
            is SyncState.Syncing,
            is SyncState.Progress,
            is SyncState.Retrying,
            -> {
                ListenUpLoadingIndicatorSmall(
                    modifier =
                        Modifier
                            .clickable(
                                onClickLabel = stringResource(Res.string.shell_sync_details_action),
                                role = Role.Button,
                                onClick = onClick,
                            ).padding(end = 4.dp),
                )
            }

            is SyncState.Error -> {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = stringResource(Res.string.shell_sync_error),
                    tint = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier
                            .clickable(
                                onClickLabel = stringResource(Res.string.shell_sync_details_action),
                                role = Role.Button,
                                onClick = onClick,
                            ).padding(end = 4.dp),
                )
            }

            else -> {
                // Idle, Success - show nothing
            }
        }

        // Sync details dropdown anchored to this indicator
        if (syncIndicatorUiState != null) {
            SyncDetailsDropdown(
                expanded = isSyncDetailsExpanded,
                state = syncIndicatorUiState,
                onRetryOperation = onRetryOperation,
                onDismissOperation = onDismissOperation,
                onRetryAll = onRetryAll,
                onDismissAll = onDismissAll,
                onDismiss = onSyncDetailsDismiss,
            )
        }
    }
}
