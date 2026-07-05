package com.calypsan.listenup.client.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.ProfileEditRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.core.resolveNameFields
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
     * [hasImageAvatar] mirrors the observed `public_profiles` avatar type (the single avatar
     * source) — it gates the "remove avatar" affordance without [User] carrying avatar state.
     */
    data class Ready(
        val user: User,
        val firstName: String,
        val lastName: String,
        val tagline: String,
        val currentPassword: String,
        val newPassword: String,
        val confirmPassword: String,
        val avatarChange: AvatarChange,
        val hasImageAvatar: Boolean,
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
@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileViewModel(
    private val profileEditRepository: ProfileEditRepository,
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
) : ViewModel() {
    private val savingFlow = MutableStateFlow(false)
    private val formFlow = MutableStateFlow(FormState())
    private var formInitialized = false

    private val eventChannel = Channel<EditProfileEvent>(Channel.BUFFERED)
    val events: Flow<EditProfileEvent> = eventChannel.receiveAsFlow()

    /**
     * The current user paired with whether they have an image avatar — the latter read reactively
     * from the observed `public_profiles` row (the single avatar source), so "remove avatar"
     * gating stays consistent with what the avatar actually renders.
     */
    private val userWithAvatar: Flow<Pair<User, Boolean>?> =
        userRepository.observeCurrentUser().flatMapLatest { user ->
            if (user == null) {
                flowOf(null)
            } else {
                userProfileRepository
                    .observeProfile(user.id.value)
                    .map { profile -> user to (profile?.avatarType == "image") }
            }
        }

    val state: StateFlow<EditProfileUiState> =
        combine(
            userWithAvatar,
            formFlow,
            savingFlow,
        ) { pair, form, isSaving ->
            if (pair == null) {
                EditProfileUiState.Error("No user data available")
            } else {
                val (user, hasImageAvatar) = pair
                // Seed the form exactly once from the first non-null user so that
                // re-emissions from Room don't overwrite edits already in progress, then
                // build Ready from the seeded values in the same pass. We must NOT seed and
                // return Loading awaiting a re-emission: when the user has no name/tagline the
                // seeded FormState equals the initial FormState(), StateFlow conflates the
                // identical assignment away, no re-emission arrives, and the screen is stuck
                // on the spinner forever.
                val (seedFirst, seedLast) = resolveNameFields(user.displayName, user.firstName, user.lastName)

                val effectiveForm =
                    if (!formInitialized) {
                        formInitialized = true
                        FormState(
                            firstName = seedFirst,
                            lastName = seedLast,
                            tagline = user.tagline ?: "",
                        ).also { formFlow.value = it }
                    } else {
                        form
                    }

                val isDirty =
                    effectiveForm.firstName != seedFirst ||
                        effectiveForm.lastName != seedLast ||
                        effectiveForm.tagline != (user.tagline ?: "") ||
                        effectiveForm.currentPassword.isNotEmpty() ||
                        effectiveForm.newPassword.isNotEmpty() ||
                        effectiveForm.confirmPassword.isNotEmpty() ||
                        effectiveForm.avatarChange != AvatarChange.None

                EditProfileUiState.Ready(
                    user = user,
                    firstName = effectiveForm.firstName,
                    lastName = effectiveForm.lastName,
                    tagline = effectiveForm.tagline,
                    currentPassword = effectiveForm.currentPassword,
                    newPassword = effectiveForm.newPassword,
                    confirmPassword = effectiveForm.confirmPassword,
                    avatarChange = effectiveForm.avatarChange,
                    hasImageAvatar = hasImageAvatar,
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

        if (anyPasswordField(form) && !passwordFieldsValid(form)) return

        viewModelScope.launch {
            savingFlow.value = true
            // No catch: a cancellation propagates through `finally` (which clears the
            // saving flag) and is never swallowed.
            try {
                if (!applyAvatarChange(form.avatarChange)) return@launch
                if (!applyProfileChanges(form, user)) return@launch

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
            } finally {
                savingFlow.value = false
            }
        }
    }

    /** True when the user has started typing into any of the three password fields. */
    private fun anyPasswordField(form: FormState): Boolean =
        form.currentPassword.isNotEmpty() ||
            form.newPassword.isNotEmpty() ||
            form.confirmPassword.isNotEmpty()

    /** Validate the staged password change; emits [EditProfileEvent.SaveFailed] and returns false on error. */
    private fun passwordFieldsValid(form: FormState): Boolean {
        if (form.newPassword != form.confirmPassword) {
            eventChannel.trySend(EditProfileEvent.SaveFailed("Passwords do not match."))
            return false
        }
        if (form.newPassword.length < PASSWORD_MIN) {
            eventChannel.trySend(
                EditProfileEvent.SaveFailed("Password must be at least $PASSWORD_MIN characters."),
            )
            return false
        }
        return true
    }

    /**
     * Apply any staged avatar operation (a separate REST transport that must complete before
     * the profile RPC). Returns true to continue, or emits [EditProfileEvent.SaveFailed] and
     * returns false on error.
     */
    private suspend fun applyAvatarChange(change: AvatarChange): Boolean {
        val result =
            when (change) {
                is AvatarChange.Upload -> profileEditRepository.uploadAvatar(change.bytes, change.contentType)
                AvatarChange.RevertToAuto -> profileEditRepository.revertToAutoAvatar()
                AvatarChange.None -> return true
            }
        return when (result) {
            is AppResult.Success -> {
                true
            }
            is AppResult.Failure -> {
                logger.error { "Avatar change failed: ${result.error}" }
                val message = if (change is AvatarChange.Upload) "Failed to upload avatar." else "Failed to revert avatar."
                eventChannel.trySend(EditProfileEvent.SaveFailed(message))
                false
            }
        }
    }

    /**
     * Persist all changed text fields (and password, if staged) in a single
     * [ProfileEditRepository.updateProfile] call — only the fields that actually changed are
     * sent. Returns true (including the no-op case) or emits [EditProfileEvent.SaveFailed] and
     * returns false on error.
     */
    private suspend fun applyProfileChanges(
        form: FormState,
        user: User,
    ): Boolean {
        val changedTagline = form.tagline.takeIf { it != (user.tagline ?: "") }
        val (baselineFirst, baselineLast) = resolveNameFields(user.displayName, user.firstName, user.lastName)
        val nameChanged = form.firstName != baselineFirst || form.lastName != baselineLast
        val passwordChange =
            if (anyPasswordField(form)) {
                PasswordChange(currentPassword = form.currentPassword, newPassword = form.newPassword)
            } else {
                null
            }

        if (!nameChanged && changedTagline == null && passwordChange == null) return true

        return when (
            val result =
                profileEditRepository.updateProfile(
                    firstName = if (nameChanged) form.firstName else null,
                    lastName = if (nameChanged) form.lastName else null,
                    tagline = changedTagline,
                    password = passwordChange,
                )
        ) {
            is AppResult.Success -> {
                true
            }
            is AppResult.Failure -> {
                logger.error { "Profile update failed: ${result.error}" }
                eventChannel.trySend(EditProfileEvent.SaveFailed("Failed to save profile."))
                false
            }
        }
    }

    companion object {
        /** Maximum characters allowed in the tagline field. */
        const val MAX_TAGLINE_LENGTH = 60

        private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
