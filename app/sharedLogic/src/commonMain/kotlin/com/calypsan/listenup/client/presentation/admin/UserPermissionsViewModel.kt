package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.advertisedPermissions
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.auth.PermissionGroup
import com.calypsan.listenup.api.dto.auth.UserPermissionsPatch
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.granting
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.PermissionPreset
import com.calypsan.listenup.client.domain.model.UserPermissions
import com.calypsan.listenup.client.domain.model.applyTo
import com.calypsan.listenup.client.domain.model.presetFor
import com.calypsan.listenup.client.domain.model.presetsApply
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One user's role and permission flags, edited as a draft and saved together.
 *
 * Nothing is sent until [save], and [save] sends only what changed — the role if it moved, and a
 * [UserPermissionsPatch] naming only the changed flags, so an untouched flag is never written. Only the
 * flags the server advertises are shown or sent. Presets are a reading of the draft ([presetFor]) and a
 * way to fill it ([selectPreset]); they are never stored.
 *
 * Promoting to Admin asks first ([UserPermissionsUiState.Ready.isConfirmingAdminPromotion]); demoting does
 * not. The owner is protected: nothing can be drafted.
 */
class UserPermissionsViewModel(
    private val userId: String,
    private val adminRepository: AdminRepository,
    private val instanceRepository: InstanceRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val session = MutableStateFlow<Session>(Session.Loading)

    val state: StateFlow<UserPermissionsUiState> =
        session
            .map { it.toUiState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPermissionsUiState.Loading)

    init {
        viewModelScope.launch {
            // A failed probe reads as an older server: one toggle, no presets — never a flag it can't store.
            val advertised =
                instanceRepository.getServerInfoOrNull()?.advertisedPermissions() ?: setOf(Permission.EDIT_METADATA)
            session.value =
                when (val result = adminRepository.getUser(userId)) {
                    is AppResult.Success -> Session.Loaded.of(result.data, advertised)
                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        Session.Failed(result.error)
                    }
                }
        }
    }

    /** Fill the draft's advertised flags from [preset]. */
    fun selectPreset(preset: PermissionPreset) = editDraft { it.copy(draftFlags = preset.applyTo(it.draftFlags, it.advertised)) }

    /** Set one flag in the draft. */
    fun setPermission(
        permission: Permission,
        granted: Boolean,
    ) = editDraft { it.copy(draftFlags = it.draftFlags.granting(permission, granted)) }

    /** Change the drafted role. Admin is confirmed first; Member applies at once; Owner is never offered. */
    fun requestRole(role: UserRole) =
        editDraft { current ->
            when {
                role == UserRole.ROOT -> current
                role == UserRole.ADMIN && current.draftRole != UserRole.ADMIN -> current.copy(isConfirmingAdminPromotion = true)
                else -> current.copy(draftRole = role)
            }
        }

    /** Confirm the Admin promotion. An admin's flags are moot, so the draft's flags go back to the saved ones. */
    fun confirmAdminPromotion() =
        editDraft { it.copy(draftRole = UserRole.ADMIN, draftFlags = it.savedFlags, isConfirmingAdminPromotion = false) }

    /** Keep the current role. */
    fun cancelAdminPromotion() = editDraft { it.copy(isConfirmingAdminPromotion = false) }

    /** Throw the draft away. */
    fun discard() = editDraft { it.copy(draftRole = it.savedRole, draftFlags = it.savedFlags, error = null) }

    /** Send the changed role and flags. On success the saved user is the new baseline. */
    fun save() {
        val loaded = session.value as? Session.Loaded ?: return
        if (!loaded.hasChanges || loaded.isSaving || loaded.isProtected) return
        session.value = loaded.copy(isSaving = true, error = null)
        viewModelScope.launch {
            when (
                val result =
                    adminRepository.updateUser(userId = userId, role = loaded.roleChange, permissions = loaded.permissionsPatch)
            ) {
                is AppResult.Success -> {
                    session.value = Session.Loaded.of(result.data, loaded.advertised)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    session.update { (it as? Session.Loaded)?.copy(isSaving = false, error = result.error) ?: it }
                }
            }
        }
    }

    private fun editDraft(transform: (Session.Loaded) -> Session.Loaded) {
        session.update { current ->
            if (current is Session.Loaded && !current.isProtected && !current.isSaving) transform(current) else current
        }
    }

    /** What the screen has: nothing yet, a load failure, or the saved user beside its draft. */
    private sealed interface Session {
        /** Loading the user and the advertised flags. */
        data object Loading : Session

        /** The user could not be loaded. */
        data class Failed(
            val error: AppError,
        ) : Session

        /** The saved role and flags, the draft beside them, and the save state. */
        data class Loaded(
            val user: AdminUserInfo,
            internal val advertised: Set<Permission>,
            val savedRole: UserRole,
            val savedFlags: UserPermissions,
            val draftRole: UserRole,
            val draftFlags: UserPermissions,
            val isConfirmingAdminPromotion: Boolean = false,
            val isSaving: Boolean = false,
            val error: AppError? = null,
        ) : Session {
            val isProtected: Boolean get() = user.isProtected

            /** The toggles this server can store, in screen order. */
            internal val shown: List<Permission> get() = Permission.known.filter { it in advertised }

            /** Flags that differ from the saved ones — none while the draft is an admin, whose flags are moot. */
            internal val changedPermissions: List<Permission>
                get() =
                    if (draftRole != UserRole.MEMBER) {
                        emptyList()
                    } else {
                        shown.filter { draftFlags.allows(it) != savedFlags.allows(it) }
                    }

            val roleChange: UserRole? get() = draftRole.takeIf { it != savedRole }

            val hasChanges: Boolean get() = roleChange != null || changedPermissions.isNotEmpty()

            val permissionsPatch: UserPermissionsPatch?
                get() =
                    changedPermissions
                        .fold(UserPermissionsPatch()) { patch, permission -> patch.granting(permission, draftFlags.allows(permission)) }
                        .takeUnless { it.isEmpty }

            companion object {
                fun of(
                    user: AdminUserInfo,
                    advertised: Set<Permission>,
                ): Loaded {
                    val role = UserRole.entries.firstOrNull { it.name.equals(user.role, ignoreCase = true) } ?: UserRole.MEMBER
                    return Loaded(user, advertised, role, user.permissions, role, user.permissions)
                }
            }
        }

        fun toUiState(): UserPermissionsUiState =
            when (this) {
                Loading -> {
                    UserPermissionsUiState.Loading
                }

                is Failed -> {
                    UserPermissionsUiState.Error(error)
                }

                is Loaded -> {
                    UserPermissionsUiState.Ready(
                        user = user,
                        role = draftRole,
                        flags = draftFlags,
                        sections =
                            PermissionGroup.entries
                                .filterNot { it == PermissionGroup.UNKNOWN }
                                .mapNotNull { group ->
                                    shown
                                        .filter { it.group == group }
                                        .takeIf { it.isNotEmpty() }
                                        ?.let { permissions ->
                                            PermissionSection(
                                                group = group,
                                                rows =
                                                    permissions.map {
                                                        PermissionRow(
                                                            permission = it,
                                                            granted = draftFlags.allows(it),
                                                            isUnsaved = draftFlags.allows(it) != savedFlags.allows(it),
                                                        )
                                                    },
                                            )
                                        }
                                },
                        preset = presetFor(draftFlags, advertised),
                        presetsShown = presetsApply(advertised),
                        curateWarningShown =
                            draftRole == UserRole.MEMBER &&
                                Permission.CURATE_LIBRARY in advertised &&
                                draftFlags.canCurateLibrary &&
                                !savedFlags.canCurateLibrary,
                        changeCount = (if (roleChange != null) 1 else 0) + changedPermissions.size,
                        isProtected = isProtected,
                        isConfirmingAdminPromotion = isConfirmingAdminPromotion,
                        isSaving = isSaving,
                        error = error,
                    )
                }
            }
    }
}

/**
 * The permissions screen's state.
 * - [Loading] before the user and the server's advertised flags have loaded.
 * - [Ready] carries the draft (role, flags), the grouped toggles, the preset reading and the save state.
 * - [Error] when the user could not be loaded.
 */
sealed interface UserPermissionsUiState {
    /** Loading the user and the server's advertised flags. */
    data object Loading : UserPermissionsUiState

    /**
     * The draft and everything the screen shows.
     *
     * @property user the user being edited, as last saved.
     * @property role the drafted role; anything but MEMBER shows "Admins can do everything".
     * @property flags the drafted flags.
     * @property sections the advertised toggles, grouped, in screen order.
     * @property preset the preset [flags] match, or CUSTOM.
     * @property presetsShown false against a server that advertises a single flag.
     * @property curateWarningShown Curate library is being granted and is not yet saved.
     * @property changeCount how many changes Save would send (the role counts as one).
     * @property isProtected the owner, whose role and flags cannot change.
     * @property isConfirmingAdminPromotion the "Make … an admin?" confirmation is open.
     * @property isSaving a save is in flight.
     * @property error the last save's failure, until the next save or discard.
     */
    data class Ready(
        val user: AdminUserInfo,
        val role: UserRole,
        val flags: UserPermissions,
        val sections: List<PermissionSection>,
        val preset: PermissionPreset,
        val presetsShown: Boolean,
        val curateWarningShown: Boolean,
        val changeCount: Int,
        val isProtected: Boolean,
        val isConfirmingAdminPromotion: Boolean,
        val isSaving: Boolean,
        val error: AppError?,
    ) : UserPermissionsUiState {
        /** Whether anything is unsaved. */
        val hasChanges: Boolean get() = changeCount > 0

        /** Whether the drafted role holds every permission, so no toggles show. */
        val isAdminRole: Boolean get() = role != UserRole.MEMBER
    }

    /** The user could not be loaded. */
    data class Error(
        val error: AppError,
    ) : UserPermissionsUiState
}

/** One group's toggles. */
data class PermissionSection(
    val group: PermissionGroup,
    val rows: List<PermissionRow>,
)

/** One toggle: its permission, the drafted value, and whether it differs from the saved one. */
data class PermissionRow(
    val permission: Permission,
    val granted: Boolean,
    val isUnsaved: Boolean,
)
