package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.advertisedPermissions
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.accessLabelFor
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the user detail screen: who one user is, and the access label the user lists show.
 *
 * Read-only. Their role and permissions are edited as a draft by [UserPermissionsViewModel]. The user
 * is read from the Room-backed roster, so a save on that screen shows here once its roster frame lands;
 * only a user the roster has not synced yet is fetched from the server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserDetailViewModel(
    private val userId: String,
    private val adminRepository: AdminRepository,
    instanceRepository: InstanceRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<UserDetailUiState> =
        flow {
            // A failed probe reads as an older server, whose labels name members by role.
            val advertised =
                instanceRepository.getServerInfoOrNull()?.advertisedPermissions() ?: setOf(Permission.EDIT_METADATA)
            emitAll(
                adminRepository.observeUser(userId).transformLatest { mirrored ->
                    emit(stateFor(mirrored ?: fetchUnsyncedUser() ?: return@transformLatest, advertised))
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserDetailUiState.Loading)

    private fun stateFor(
        user: AdminUserInfo,
        advertised: Set<Permission>,
    ): UserDetailUiState = UserDetailUiState.Ready(user = user.copy(access = accessLabelFor(user, advertised)))

    /** The server's copy of a user the roster has not synced, or null after emitting [UserDetailUiState.Error]. */
    private suspend fun FlowCollector<UserDetailUiState>.fetchUnsyncedUser(): AdminUserInfo? =
        when (val result = adminRepository.getUser(userId)) {
            is AppResult.Success -> {
                result.data
            }

            is AppResult.Failure -> {
                errorBus.emit(result.error)
                logger.error { "Failed to load user: $userId — ${result.error}" }
                emit(UserDetailUiState.Error(error = result.error))
                null
            }
        }
}

/**
 * UI state for the user detail screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the user is first read.
 * - [Ready] once the user has loaded; it follows every roster change.
 * - [Error] when a user the roster has not synced cannot be fetched.
 */
sealed interface UserDetailUiState {
    /** Before the user is first read. */
    data object Loading : UserDetailUiState

    /** The user has loaded, with the access label the user lists show. */
    data class Ready(
        val user: AdminUserInfo,
    ) : UserDetailUiState

    /** A user the roster has not synced could not be fetched. */
    data class Error(
        val error: AppError,
    ) : UserDetailUiState
}
