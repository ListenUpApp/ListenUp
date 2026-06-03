package com.calypsan.listenup.client.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ProfileEditRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/** UI state for the Edit Profile screen. */
sealed interface EditProfileUiState {
    /** Initial state before the observe pipeline emits. */
    data object Loading : EditProfileUiState

    /** User loaded; ready to edit. [isSaving] overlays on top of data during mutations. */
    data class Ready(
        val user: User,
        val localAvatarPath: String?,
        val isSaving: Boolean,
    ) : EditProfileUiState

    /** No user available — signed out, or local cache empty. */
    data class Error(
        val message: String,
    ) : EditProfileUiState
}

/** One-shot outcomes the screen surfaces via snackbar. */
sealed interface EditProfileEvent {
    data object TaglineSaved : EditProfileEvent

    data object NameSaved : EditProfileEvent

    data object AvatarUpdated : EditProfileEvent

    data object PasswordChanged : EditProfileEvent

    /** A save operation failed; [message] is surfaced in a snackbar. */
    data class SaveFailed(
        val message: String,
    ) : EditProfileEvent
}

/**
 * ViewModel for the Edit Profile screen.
 *
 * Observes the current user from local storage (offline-first) and layers an `isSaving`
 * overlay on top while a write is in flight. Save outcomes surface via the [events]
 * channel — the UI collects them and shows snackbars without polling state flags.
 *
 * Text input state (tagline, first/last name, passwords) lives in the UI as
 * `rememberSaveable` — the VM receives complete values at save time.
 */
class EditProfileViewModel(
    private val profileEditRepository: ProfileEditRepository,
    private val userRepository: UserRepository,
    private val imageRepository: ImageRepository,
) : ViewModel() {
    private val savingFlow = MutableStateFlow(false)

    private val eventChannel = Channel<EditProfileEvent>(Channel.BUFFERED)
    val events: Flow<EditProfileEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<EditProfileUiState> =
        combine(userRepository.observeCurrentUser(), savingFlow) { user, isSaving ->
            if (user == null) {
                EditProfileUiState.Error("No user data available")
            } else {
                EditProfileUiState.Ready(
                    user = user,
                    localAvatarPath = resolveLocalAvatarPath(user),
                    isSaving = isSaving,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = EditProfileUiState.Loading,
        )

    fun saveTagline(tagline: String) {
        val normalized = tagline.take(MAX_TAGLINE_LENGTH).ifEmpty { null }
        runSave(EditProfileEvent.TaglineSaved, "Failed to save tagline") {
            profileEditRepository.updateTagline(normalized)
        }
    }

    fun saveName(
        firstName: String,
        lastName: String,
    ) {
        runSave(EditProfileEvent.NameSaved, "Failed to save name") {
            profileEditRepository.updateName(firstName, lastName)
        }
    }

    fun uploadAvatar(
        imageData: ByteArray,
        contentType: String,
    ) {
        runSave(EditProfileEvent.AvatarUpdated, "Failed to upload avatar") {
            profileEditRepository.uploadAvatar(imageData, contentType)
        }
    }

    fun revertToAutoAvatar() {
        runSave(EditProfileEvent.AvatarUpdated, "Failed to revert avatar") {
            profileEditRepository.revertToAutoAvatar()
        }
    }

    fun changePassword(
        currentPassword: String,
        newPassword: String,
    ) {
        if (newPassword.length < PASSWORD_MIN) {
            eventChannel.trySend(
                EditProfileEvent.SaveFailed("Password must be at least $PASSWORD_MIN characters"),
            )
            return
        }
        runSave(EditProfileEvent.PasswordChanged, "Failed to change password") {
            profileEditRepository.changePassword(currentPassword, newPassword)
        }
    }

    private fun runSave(
        onSuccess: EditProfileEvent,
        failureMessage: String,
        op: suspend () -> AppResult<*>,
    ) {
        viewModelScope.launch {
            savingFlow.value = true
            try {
                when (val result = op()) {
                    is AppResult.Success -> {
                        eventChannel.trySend(onSuccess)
                        logger.info { "Save succeeded: $onSuccess" }
                    }

                    is AppResult.Failure -> {
                        logger.error { "Save failed: $failureMessage — ${result.message}" }
                        eventChannel.trySend(EditProfileEvent.SaveFailed(failureMessage))
                    }
                }
            } finally {
                savingFlow.value = false
            }
        }
    }

    private fun resolveLocalAvatarPath(user: User): String? =
        if (user.avatarType == "image" && imageRepository.userAvatarExists(user.id.value)) {
            imageRepository.getUserAvatarPath(user.id.value)
        } else {
            null
        }

    companion object {
        /** Maximum characters allowed in the tagline field. */
        const val MAX_TAGLINE_LENGTH = 60

        private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
