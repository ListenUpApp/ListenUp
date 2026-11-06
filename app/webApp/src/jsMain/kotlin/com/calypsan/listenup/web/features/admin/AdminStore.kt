package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.web.nav.RouteSession
import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.client.presentation.admin.AdminUiState
import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open Admin session: who is on this server, and every decision an admin can make about them.
 *
 * One ViewModel covers all of it, which is why "people" is a coherent first slice of a sidebar
 * entry that hides eleven areas. Collections, categories, the inbox, backups, imports and server
 * settings each have their own ViewModel and arrive as their own pages.
 */
class AdminSession(
    val state: StateFlow<AdminUiState>,
    val onApproveUser: (String) -> Unit,
    val onDenyUser: (String) -> Unit,
    val onDeleteUser: (String) -> Unit,
    val onRevokeInvite: (String) -> Unit,
    val onDecidePasswordReset: (requestId: String, approved: Boolean) -> Unit,
    val onDismissResetCode: () -> Unit,
    val onSetRegistrationPolicy: (RegistrationPolicy) -> Unit,
    val onClearError: () -> Unit,
    val onRetry: () -> Unit,
    override val close: () -> Unit,
) : RouteSession

/** How the page gets its session. */
typealias OpenAdmin = () -> AdminSession

/**
 * The production source: the shared [AdminViewModel] over the started graph.
 *
 * `loadData` fires here, as the shelf-detail session does: the load is what the session IS, and
 * leaving it to the page means every future caller has to remember the same two-step.
 */
fun graphAdmin(koin: Koin): OpenAdmin =
    {
        val viewModel = koin.get<AdminViewModel>()
        val store = ViewModelStore().apply { put(ADMIN_STORE_KEY, viewModel) }
        viewModel.loadData()
        AdminSession(
            state = viewModel.state,
            onApproveUser = viewModel::approveUser,
            onDenyUser = viewModel::denyUser,
            onDeleteUser = viewModel::deleteUser,
            onRevokeInvite = viewModel::revokeInvite,
            onDecidePasswordReset = viewModel::decidePasswordReset,
            onDismissResetCode = viewModel::dismissResetCode,
            onSetRegistrationPolicy = viewModel::setRegistrationPolicy,
            onClearError = viewModel::clearError,
            onRetry = viewModel::loadData,
            close = store::clear,
        )
    }

private const val ADMIN_STORE_KEY = "admin"
