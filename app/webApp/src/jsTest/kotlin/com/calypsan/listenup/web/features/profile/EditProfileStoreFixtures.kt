package com.calypsan.listenup.web.features.profile

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.presentation.profile.EditProfileEvent
import com.calypsan.listenup.client.presentation.profile.EditProfileUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedEditProfile(
    state: EditProfileUiState,
    events: Flow<EditProfileEvent> = emptyFlow(),
    onFirstName: (String) -> Unit = {},
    onLastName: (String) -> Unit = {},
    onTagline: (String) -> Unit = {},
    onCurrentPassword: (String) -> Unit = {},
    onNewPassword: (String) -> Unit = {},
    onConfirmPassword: (String) -> Unit = {},
    onPickAvatar: (ByteArray, String, ImageFrame?) -> Unit = { _, _, _ -> },
    onReframeAvatar: (ImageFrame) -> Unit = {},
    onRemoveAvatar: () -> Unit = {},
    onSave: () -> Unit = {},
): OpenEditProfile =
    {
        EditProfileSession(
            state = MutableStateFlow(state),
            events = events,
            onFirstName = onFirstName,
            onLastName = onLastName,
            onTagline = onTagline,
            onCurrentPassword = onCurrentPassword,
            onNewPassword = onNewPassword,
            onConfirmPassword = onConfirmPassword,
            onPickAvatar = onPickAvatar,
            onReframeAvatar = onReframeAvatar,
            onRemoveAvatar = onRemoveAvatar,
            onSave = onSave,
            close = {},
        )
    }
