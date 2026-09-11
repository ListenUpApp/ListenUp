package com.calypsan.listenup.web.features.admin

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.admin.CreateInviteUiState
import com.calypsan.listenup.client.presentation.admin.CreateInviteViewModel
import com.calypsan.listenup.client.presentation.admin.UserDetailUiState
import com.calypsan.listenup.client.presentation.admin.UserDetailViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/** An open create-invite session. */
class CreateInviteSession(
    val state: StateFlow<CreateInviteUiState>,
    val onCreate: (email: String, role: String, expiresInDays: Int) -> Unit,
    val onClearError: () -> Unit,
    val onReset: () -> Unit,
    val close: () -> Unit,
)

/** How the invite form gets its state. */
typealias OpenCreateInvite = () -> CreateInviteSession

/** The production source: the shared [CreateInviteViewModel]. */
fun graphCreateInvite(koin: Koin): OpenCreateInvite =
    {
        val viewModel = koin.get<CreateInviteViewModel>()
        val store = ViewModelStore().apply { put("createInvite", viewModel) }
        CreateInviteSession(
            state = viewModel.state,
            onCreate = viewModel::createInvite,
            onClearError = viewModel::clearError,
            onReset = viewModel::reset,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedCreateInvite(
    state: CreateInviteUiState,
    onCreate: (String, String, Int) -> Unit = { _, _, _ -> },
    onClearError: () -> Unit = {},
    onReset: () -> Unit = {},
): OpenCreateInvite =
    {
        CreateInviteSession(
            state = MutableStateFlow(state),
            onCreate = onCreate,
            onClearError = onClearError,
            onReset = onReset,
            close = {},
        )
    }

/** An open session over one member's permissions. */
class UserDetailSession(
    val state: StateFlow<UserDetailUiState>,
    val onToggleCanEdit: () -> Unit,
    val onToggleCanShare: () -> Unit,
    val close: () -> Unit,
)

/** How the member page gets its state. */
typealias OpenUserDetail = (userId: String) -> UserDetailSession

/**
 * The production source: the shared [UserDetailViewModel], parametrized on the member.
 *
 * ⛔ The user id is a *constructor* parameter, not a `load()` call — this ViewModel loads in its
 * own `init`, so resolving it bare would fetch whoever the graph happened to hand back.
 */
fun graphUserDetail(koin: Koin): OpenUserDetail =
    { userId ->
        val viewModel = koin.get<UserDetailViewModel> { parametersOf(userId) }
        val store = ViewModelStore().apply { put(userId, viewModel) }
        UserDetailSession(
            state = viewModel.state,
            onToggleCanEdit = viewModel::toggleCanEdit,
            onToggleCanShare = viewModel::toggleCanShare,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedUserDetail(
    state: UserDetailUiState,
    onToggleCanEdit: () -> Unit = {},
    onToggleCanShare: () -> Unit = {},
    onOpen: (String) -> Unit = {},
): OpenUserDetail =
    { userId ->
        onOpen(userId)
        UserDetailSession(
            state = MutableStateFlow(state),
            onToggleCanEdit = onToggleCanEdit,
            onToggleCanShare = onToggleCanShare,
            close = {},
        )
    }
