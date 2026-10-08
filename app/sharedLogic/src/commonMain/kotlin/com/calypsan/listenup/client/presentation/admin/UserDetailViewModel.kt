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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the user detail screen: who one user is, and the access label the user lists show.
 *
 * Read-only. Their role and permissions are edited as a draft by [UserPermissionsViewModel].
 */
class UserDetailViewModel(
    private val userId: String,
    private val adminRepository: AdminRepository,
    private val instanceRepository: InstanceRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<UserDetailUiState>
        field = MutableStateFlow<UserDetailUiState>(UserDetailUiState.Loading)

    init {
        viewModelScope.launch {
            // A failed probe reads as an older server, whose labels name members by role.
            val advertised =
                instanceRepository.getServerInfoOrNull()?.advertisedPermissions() ?: setOf(Permission.EDIT_METADATA)
            state.value =
                when (val result = adminRepository.getUser(userId)) {
                    is AppResult.Success -> {
                        val user = result.data
                        UserDetailUiState.Ready(user = user.copy(access = accessLabelFor(user, advertised)))
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        logger.error { "Failed to load user: $userId — ${result.error}" }
                        UserDetailUiState.Error(error = result.error)
                    }
                }
        }
    }
}

/**
 * UI state for the user detail screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first `getUser` response.
 * - [Ready] once the user has loaded.
 * - [Error] terminal state when the load fails.
 */
sealed interface UserDetailUiState {
    /** Before the first `getUser` response. */
    data object Loading : UserDetailUiState

    /** The user has loaded, with the access label the user lists show. */
    data class Ready(
        val user: AdminUserInfo,
    ) : UserDetailUiState

    /** Terminal state when the user load fails. */
    data class Error(
        val error: AppError,
    ) : UserDetailUiState
}
