package com.calypsan.listenup.client.features.shell.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.UserAvatarMenu
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_search
import listenup.composeapp.generated.resources.shell_close_search
import listenup.composeapp.generated.resources.shell_search_audiobooks
import listenup.composeapp.generated.resources.shell_sync_details_action
import listenup.composeapp.generated.resources.shell_sync_error
import org.jetbrains.compose.resources.stringResource

/**
 * A pre-wired shell header the screens place at the top of their scroll. The shell binds the
 * trailing actions (search/sync/avatar); the screen supplies the leading hero (greeting or title).
 */
typealias AppHeaderSlot = @Composable (leadingContent: @Composable () -> Unit) -> Unit

/**
 * Custom shell header — the design uses its own header band, not a Material `TopAppBar`.
 *
 * A single row: a screen-supplied [leadingContent] hero (the Home greeting, a screen title) on the
 * left, and the shell's trailing actions (search, sync indicator, avatar menu) vertically centred
 * against it on the right. The header is placed at the top of each screen's scroll, so it scrolls
 * away with content.
 *
 * Collapsed: leading hero + search icon + sync indicator + avatar.
 * Search expanded: back arrow + full-width search field (the hero and other actions step aside).
 *
 * @param leadingContent The hero slot — greeting on Home, a title elsewhere.
 * @param syncState Current sync status.
 * @param user Current user entity.
 * @param isSearchExpanded Whether search is expanded.
 * @param searchQuery Current search query.
 * @param onSearchExpandedChange Callback when search expand state changes.
 * @param onSearchQueryChange Callback when search query changes.
 * @param isAvatarMenuExpanded Whether the avatar dropdown is expanded.
 * @param onAvatarMenuExpandedChange Callback when the avatar menu expand state changes.
 * @param onAdminClick Callback when administration is clicked (only shown for admin users).
 * @param onSettingsClick Callback when settings is clicked.
 * @param onSignOutClick Callback when sign out is clicked.
 * @param onMyProfileClick Callback when "my profile" is clicked.
 * @param onSyncIndicatorClick Callback when the sync indicator is clicked.
 * @param isSyncDetailsExpanded Whether the sync details dropdown is expanded.
 * @param syncIndicatorUiState UI state for the sync details dropdown content.
 * @param onRetryOperation Callback when a failed operation retry is clicked.
 * @param onDismissOperation Callback when a failed operation dismiss is clicked.
 * @param onRetryAll Callback when retry all is clicked.
 * @param onDismissAll Callback when dismiss all is clicked.
 * @param onSyncDetailsDismiss Callback when the sync details dropdown is dismissed.
 * @param showAvatar Whether to show the avatar menu.
 * @param showAvatarLabel Whether the avatar menu shows the user's name label.
 * @param modifier Modifier from the placing screen.
 */
@Suppress("LongParameterList")
@Composable
fun AppHeader(
    leadingContent: @Composable () -> Unit,
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
    showAvatar: Boolean = true,
    showAvatarLabel: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // The persistent inline search box needs real room beside the hero + avatar — only show it on
    // genuinely wide (expanded) chrome. Medium widths (e.g. tablet portrait) keep the compact icon.
    val isWide =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )
    AnimatedContent(
        // On wide windows search is a persistent inline field (below); never run the compact
        // full-width takeover even when [isSearchExpanded] flips true from typing into it.
        targetState = isSearchExpanded && !isWide,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "header_search_animation",
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = Spacing.screenMargin, end = 8.dp, top = 8.dp, bottom = 0.dp),
    ) { expanded ->
        if (expanded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onSearchExpandedChange(false) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.shell_close_search),
                    )
                }
                SearchField(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onClose = { onSearchExpandedChange(false) },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    leadingContent()
                }

                if (isWide) {
                    // Wide chrome: a persistent search box, not a tap-to-expand icon (matches design).
                    HeaderSearchField(
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        onExpandedChange = onSearchExpandedChange,
                        modifier = Modifier.width(320.dp).height(52.dp).padding(end = 8.dp),
                    )
                } else {
                    IconButton(onClick = { onSearchExpandedChange(true) }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(Res.string.common_search),
                        )
                    }
                }

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

                if (showAvatar) {
                    UserAvatarMenu(
                        user = user,
                        expanded = isAvatarMenuExpanded,
                        onExpandedChange = onAvatarMenuExpandedChange,
                        onMyProfileClick = onMyProfileClick,
                        onAdminClick = onAdminClick,
                        onSettingsClick = onSettingsClick,
                        onSignOutClick = onSignOutClick,
                        showLabel = showAvatarLabel,
                    )
                }
            }
        }
    }
}

/** Search text field for the expanded search state. */
@Composable
@Suppress("UNUSED_PARAMETER")
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
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
            modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
    )
}

/**
 * Persistent inline search box shown in wide chrome — typing drives the query and opens the
 * results overlay (via [onExpandedChange]); clearing the text closes it. Compact chrome uses the
 * tap-to-expand [SearchField] takeover instead.
 */
@Composable
private fun HeaderSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = query,
        onValueChange = {
            onQueryChange(it)
            onExpandedChange(it.isNotBlank())
        },
        placeholder = { Text(stringResource(Res.string.common_search)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(Res.string.common_search),
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        modifier = modifier,
    )
}

/**
 * Sync status indicator with anchored dropdown.
 *
 * Shows a spinner during Syncing/Progress/Retrying, an error icon on Error, and nothing for
 * Idle/Success. Tappable to show the sync details dropdown.
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
                            ).padding(horizontal = 12.dp, vertical = 12.dp),
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
                            ).padding(horizontal = 12.dp, vertical = 12.dp),
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
