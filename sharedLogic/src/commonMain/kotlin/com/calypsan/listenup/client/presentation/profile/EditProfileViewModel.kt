package com.calypsan.listenup.client.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ProfileEditRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/** How the avatar will be changed when the user taps Save. */
sealed interface AvatarChange {
    /** No pending avatar change. */
    data object None : AvatarChange

    /** Revert to auto-generated initials avatar. */
    data object RevertToAuto : AvatarChange

    /** Upload [bytes] as the new avatar with the given [contentType]. */
    data class Upload(
        val bytes: ByteArray,
        val contentType: String,
    ) : AvatarChange {
        // ByteArray identity semantics — equals/hashCode on content, not reference.
        override fun equals(other: Any?): Boolean =
            other is Upload && bytes.contentEquals(other.bytes) && contentType == other.contentType

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
    }
}

/** UI state for the Edit Profile screen. */
sealed interface EditProfileUiState {
    /** Initial state before the observe pipeline emits. */
    data object Loading : EditProfileUiState

    /**
     * User loaded; ready to edit.
     *
     * The editable fields (name, tagline, passwords, avatarChange) live here so that the
     * UI only needs to reflect state — no separate `rememberSaveable` islands.
     *
     * [isDirty] is true whenever any field differs from the loaded [user] or a pending
     * avatar change is staged.
     * [isSaving] overlays on top of data while a save is in flight.
     */
    data class Ready(
        val user: User,
        val localAvatarPath: String?,
        val firstName: String,
        val lastName: String,
        val tagline: String,
        val currentPassword: String,
        val newPassword: String,
        val confirmPassword: String,
        val avatarChange: AvatarChange,
        val isDirty: Boolean,
        val isSaving: Boolean,
    ) : EditProfileUiState

    /** No user available — signed out, or local cache empty. */
    data class Error(
        val message: String,
    ) : EditProfileUiState
}

/** One-shot outcomes the screen surfaces via snackbar / navigation. */
sealed interface EditProfileEvent {
    /** All staged changes were saved successfully. */
    data object SaveSucceeded : EditProfileEvent

    /** A save operation failed; [message] is surfaced in a snackbar. */
    data class SaveFailed(
        val message: String,
    ) : EditProfileEvent
}

// ── Private form state ────────────────────────────────────────────────────────

/**
 * Mutable edit-buffer that sits inside [EditProfileViewModel].
 * Initialized once from the first non-null [User] emission.
 */
private data class FormState(
    val firstName: String = "",
    val lastName: String = "",
    val tagline: String = "",
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val avatarChange: AvatarChange = AvatarChange.None,
)

/**
 * ViewModel for the Edit Profile screen.
 *
 * Holds the entire form state ([FormState]) so the UI is a pure reflection of [state].
 * [save] performs validation, applies any staged avatar operation, then calls
 * [ProfileEditRepository.updateProfile] for all text-field changes in a single RPC.
 *
 * Save outcomes surface via [events] (a `Channel`-backed `Flow`) so the UI can show
 * a snackbar without polling state flags.
 */
class EditProfileViewModel(
    private val profileEditRepository: ProfileEditRepository,
    private val userRepository: UserRepository,
    private val imageRepository: ImageRepository,
) : ViewModel() {
    private val savingFlow = MutableStateFlow(false)
    private val formFlow = MutableStateFlow(FormState())
    private var formInitialized = false

    private val eventChannel = Channel<EditProfileEvent>(Channel.BUFFERED)
    val events: Flow<EditProfileEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<EditProfileUiState> =
        combine(
            userRepository.observeCurrentUser(),
            formFlow,
            savingFlow,
        ) { user, form, isSaving ->
            if (user == null) {
                EditProfileUiState.Error("No user data available")
            } else {
                // Seed the form exactly once from the first non-null user so that
                // re-emissions from Room don't overwrite edits already in progress.
                if (!formInitialized) {
                    formInitialized = true
                    formFlow.value =
                        FormState(
                            firstName = user.firstName ?: "",
                            lastName = user.lastName ?: "",
                            tagline = user.tagline ?: "",
                        )
                    // Return Loading briefly — the next emission will carry the seeded form.
                    return@combine EditProfileUiState.Loading
                }

                val isDirty =
                    form.firstName != (user.firstName ?: "") ||
                        form.lastName != (user.lastName ?: "") ||
                        form.tagline != (user.tagline ?: "") ||
                        form.currentPassword.isNotEmpty() ||
                        form.newPassword.isNotEmpty() ||
                        form.confirmPassword.isNotEmpty() ||
                        form.avatarChange != AvatarChange.None

                EditProfileUiState.Ready(
                    user = user,
                    localAvatarPath = resolveLocalAvatarPath(user),
                    firstName = form.firstName,
                    lastName = form.lastName,
                    tagline = form.tagline,
                    currentPassword = form.currentPassword,
                    newPassword = form.newPassword,
                    confirmPassword = form.confirmPassword,
                    avatarChange = form.avatarChange,
                    isDirty = isDirty,
                    isSaving = isSaving,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = EditProfileUiState.Loading,
        )

    // ── Setters ───────────────────────────────────────────────────────────────

    fun setFirstName(value: String) = formFlow.update { it.copy(firstName = value) }

    fun setLastName(value: String) = formFlow.update { it.copy(lastName = value) }

    fun setTagline(value: String) = formFlow.update { it.copy(tagline = value.take(MAX_TAGLINE_LENGTH)) }

    fun setCurrentPassword(value: String) = formFlow.update { it.copy(currentPassword = value) }

    fun setNewPassword(value: String) = formFlow.update { it.copy(newPassword = value) }

    fun setConfirmPassword(value: String) = formFlow.update { it.copy(confirmPassword = value) }

    fun stageAvatarUpload(
        bytes: ByteArray,
        contentType: String,
    ) = formFlow.update { it.copy(avatarChange = AvatarChange.Upload(bytes, contentType)) }

    fun stageAvatarRevert() = formFlow.update { it.copy(avatarChange = AvatarChange.RevertToAuto) }

    // ── Save ─────────────────────────────────────────────────────────────────

    /**
     * Validate the form, apply any staged avatar operation, then persist all text-field
     * changes in a single [ProfileEditRepository.updateProfile] call.
     *
     * Password validation (match + minimum length) runs before touching the network.
     * On success, transient state (password fields, avatarChange) is cleared and
     * [EditProfileEvent.SaveSucceeded] is emitted.
     * On any failure, the form is kept intact and [EditProfileEvent.SaveFailed] is emitted.
     */
    fun save() {
        val ready = state.value as? EditProfileUiState.Ready ?: return
        val form = formFlow.value
        val user = ready.user

        // Validate password fields when any of them is non-empty.
        val anyPasswordField =
            form.currentPassword.isNotEmpty() ||
                form.newPassword.isNotEmpty() ||
                form.confirmPassword.isNotEmpty()

        if (anyPasswordField) {
            if (form.newPassword != form.confirmPassword) {
                eventChannel.trySend(EditProfileEvent.SaveFailed("Passwords do not match."))
                return
            }
            if (form.newPassword.length < PASSWORD_MIN) {
                eventChannel.trySend(
                    EditProfileEvent.SaveFailed("Password must be at least $PASSWORD_MIN characters."),
                )
                return
            }
        }

        viewModelScope.launch {
            savingFlow.value = true
            try {
                // 1. Avatar operation (separate REST transport) — must complete before the RPC.
                when (val avatarChange = form.avatarChange) {
                    is AvatarChange.Upload -> {
                        when (
                            val result =
                                profileEditRepository.uploadAvatar(avatarChange.bytes, avatarChange.contentType)
                        ) {
                            is AppResult.Success -> { /* continue */ }

                            is AppResult.Failure -> {
                                logger.error { "Avatar upload failed: ${result.error}" }
                                eventChannel.trySend(EditProfileEvent.SaveFailed("Failed to upload avatar."))
                                return@launch
                            }
                        }
                    }

                    is AvatarChange.RevertToAuto -> {
                        when (val result = profileEditRepository.revertToAutoAvatar()) {
                            is AppResult.Success -> { /* continue */ }

                            is AppResult.Failure -> {
                                logger.error { "Avatar revert failed: ${result.error}" }
                                eventChannel.trySend(EditProfileEvent.SaveFailed("Failed to revert avatar."))
                                return@launch
                            }
                        }
                    }

                    AvatarChange.None -> { /* nothing to do */ }
                }

                // 2. Profile text-field update — only send changed fields.
                val changedFirstName = form.firstName.takeIf { it != (user.firstName ?: "") }
                val changedLastName = form.lastName.takeIf { it != (user.lastName ?: "") }
                val changedTagline = form.tagline.takeIf { it != (user.tagline ?: "") }
                val passwordChange =
                    if (anyPasswordField) {
                        PasswordChange(
                            currentPassword = form.currentPassword,
                            newPassword = form.newPassword,
                        )
                    } else {
                        null
                    }

                val nameChanged = changedFirstName != null || changedLastName != null
                val profileChanged = nameChanged || changedTagline != null || passwordChange != null

                if (profileChanged) {
                    when (
                        val result =
                            profileEditRepository.updateProfile(
                                firstName = if (nameChanged) form.firstName else null,
                                lastName = if (nameChanged) form.lastName else null,
                                tagline = changedTagline,
                                password = passwordChange,
                            )
                    ) {
                        is AppResult.Success -> { /* fall through */ }

                        is AppResult.Failure -> {
                            logger.error { "Profile update failed: ${result.error}" }
                            eventChannel.trySend(EditProfileEvent.SaveFailed("Failed to save profile."))
                            return@launch
                        }
                    }
                }

                // Success — clear transient fields.
                formFlow.update {
                    it.copy(
                        currentPassword = "",
                        newPassword = "",
                        confirmPassword = "",
                        avatarChange = AvatarChange.None,
                    )
                }
                logger.info { "Profile save succeeded" }
                eventChannel.trySend(EditProfileEvent.SaveSucceeded)
            } catch (e: CancellationException) {
                throw e
            } finally {
                savingFlow.value = false
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

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
