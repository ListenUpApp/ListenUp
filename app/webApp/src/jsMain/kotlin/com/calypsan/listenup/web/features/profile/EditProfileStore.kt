package com.calypsan.listenup.web.features.profile

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.profile.EditProfileEvent
import com.calypsan.listenup.client.presentation.profile.EditProfileUiState
import com.calypsan.listenup.client.presentation.profile.EditProfileViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin

/**
 * An open Edit Profile form: the state to render, every change it accepts, and the teardown.
 *
 * One callback per setter rather than a single event sink, because the shared
 * [EditProfileViewModel] exposes setters — a web-only event type wrapping them would be a second
 * vocabulary for the same six fields, and the translation between the two is exactly where a
 * mis-wired field goes unnoticed.
 */
class EditProfileSession(
    val state: StateFlow<EditProfileUiState>,
    val events: Flow<EditProfileEvent>,
    val onFirstName: (String) -> Unit,
    val onLastName: (String) -> Unit,
    val onTagline: (String) -> Unit,
    val onCurrentPassword: (String) -> Unit,
    val onNewPassword: (String) -> Unit,
    val onConfirmPassword: (String) -> Unit,
    val onPickAvatar: (ByteArray, String) -> Unit,
    val onRemoveAvatar: () -> Unit,
    val onSave: () -> Unit,
    val close: () -> Unit,
)

/** How the form gets its state. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenEditProfile = () -> EditProfileSession

/**
 * The production source: the shared [EditProfileViewModel], over whoever is signed in.
 *
 * Takes no user id, and that is the point — the ViewModel reads `observeCurrentUser()`, so the form
 * always edits the person holding the session and cannot be pointed at anyone else. The route's own
 * guard covers the other half: a URL naming somebody else must not reach this at all.
 */
fun graphEditProfile(koin: Koin): OpenEditProfile =
    {
        val viewModel = koin.get<EditProfileViewModel>()
        val store = ViewModelStore().apply { put("edit-profile", viewModel) }
        EditProfileSession(
            state = viewModel.state,
            events = viewModel.events,
            onFirstName = viewModel::setFirstName,
            onLastName = viewModel::setLastName,
            onTagline = viewModel::setTagline,
            onCurrentPassword = viewModel::setCurrentPassword,
            onNewPassword = viewModel::setNewPassword,
            onConfirmPassword = viewModel::setConfirmPassword,
            onPickAvatar = viewModel::stageAvatarUpload,
            onRemoveAvatar = viewModel::stageAvatarRevert,
            onSave = viewModel::save,
            close = store::clear,
        )
    }

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
    onPickAvatar: (ByteArray, String) -> Unit = { _, _ -> },
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
            onRemoveAvatar = onRemoveAvatar,
            onSave = onSave,
            close = {},
        )
    }
