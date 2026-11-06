package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.presentation.admin.AdminSettingsUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedServerSettings(
    state: AdminSettingsUiState = AdminSettingsUiState.Loading,
    onServerName: (String) -> Unit = {},
    onRemoteUrl: (String) -> Unit = {},
    onHoldNewBooks: (Boolean) -> Unit = {},
    onPushNotifications: (Boolean) -> Unit = {},
    onSetRatingSourceEnabled: (ExternalRatingSource, Boolean) -> Unit = { _, _ -> },
    onMetadataRegion: (String) -> Unit = {},
    onSaveHardcoverToken: (String) -> Unit = {},
    onRemoveHardcoverToken: () -> Unit = {},
    onHardcoverMetadata: (Boolean) -> Unit = {},
    onClearHardcoverTokenError: () -> Unit = {},
    onSave: () -> Unit = {},
    onClearError: () -> Unit = {},
    onRetry: () -> Unit = {},
): OpenServerSettings =
    {
        ServerSettingsSession(
            state = MutableStateFlow(state),
            onServerName = onServerName,
            onRemoteUrl = onRemoteUrl,
            onHoldNewBooks = onHoldNewBooks,
            onPushNotifications = onPushNotifications,
            onSetRatingSourceEnabled = onSetRatingSourceEnabled,
            onMetadataRegion = onMetadataRegion,
            onSaveHardcoverToken = onSaveHardcoverToken,
            onRemoveHardcoverToken = onRemoveHardcoverToken,
            onHardcoverMetadata = onHardcoverMetadata,
            onClearHardcoverTokenError = onClearHardcoverTokenError,
            onSave = onSave,
            onClearError = onClearError,
            onRetry = onRetry,
            close = {},
        )
    }
