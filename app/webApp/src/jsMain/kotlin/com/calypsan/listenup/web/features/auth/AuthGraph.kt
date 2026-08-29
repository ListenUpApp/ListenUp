package com.calypsan.listenup.web.features.auth

import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.usecase.auth.LogoutUseCase
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordUiState
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordViewModel
import com.calypsan.listenup.client.presentation.auth.LoginUiState
import com.calypsan.listenup.client.presentation.auth.LoginViewModel
import com.calypsan.listenup.client.presentation.auth.PendingApprovalUiState
import com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel
import com.calypsan.listenup.client.presentation.auth.RegisterUiState
import com.calypsan.listenup.client.presentation.auth.RegisterViewModel
import com.calypsan.listenup.client.presentation.auth.SetupUiState
import com.calypsan.listenup.client.presentation.auth.SetupViewModel
import com.calypsan.listenup.client.presentation.invite.ClaimInviteUiState
import com.calypsan.listenup.client.presentation.invite.ClaimInviteViewModel
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/**
 * How the web body reaches the shared auth layer.
 *
 * One seam rather than one per screen: the four auth ViewModels differ only in their actions, and
 * a per-screen typealias would still leave [authState] — the thing the gate actually routes on —
 * needing a fifth. Collapsing them means the `AuthState`-to-screen mapping is testable in exactly
 * one place.
 *
 * Sessions hand out plain function values, not ViewModels, so a screen cannot reach past its own
 * actions and a spec can build one as a literal with no Koin behind it.
 */
interface AuthGraph {
    /** The navigation driver. Every screen choice in [AuthGate] derives from this. */
    val authState: StateFlow<AuthState>

    /** Resolves the stored session and server status into a first real [authState] value. */
    suspend fun initialize()

    /** Re-reads whether the server allows open registration, so the "Create account" link is honest. */
    suspend fun refreshOpenRegistration()

    /** Deliberate sign-out: clears tokens and drops [authState] back to `NeedsLogin`. */
    suspend fun signOut()

    fun openLogin(): LoginSession

    fun openSetup(): SetupSession

    fun openRegister(): RegisterSession

    fun openPendingApproval(
        userId: String,
        email: String,
    ): PendingApprovalSession

    fun openForgotPassword(): ForgotPasswordSession

    fun openClaimInvite(): ClaimInviteSession
}

/**
 * An open login screen and the teardown for it.
 *
 * A browser has no `ViewModelStore` owner, so the composition owns the lifetime: it opens a
 * session when the branch appears and calls [close] when the branch goes. Unlike
 * `BookDetailViewModel`, the auth ViewModels expose a public idempotent `close()`, so no store is
 * needed.
 */
class LoginSession(
    val state: StateFlow<LoginUiState>,
    val submit: (email: String, password: String) -> Unit,
    val clearError: () -> Unit,
    val close: () -> Unit,
)

/** An open setup screen and the teardown for it. See [LoginSession]. */
class SetupSession(
    val state: StateFlow<SetupUiState>,
    val submit: (firstName: String, lastName: String, email: String, password: String, passwordConfirm: String) -> Unit,
    val clearError: () -> Unit,
    val close: () -> Unit,
)

/** An open registration screen and the teardown for it. See [LoginSession]. */
class RegisterSession(
    val state: StateFlow<RegisterUiState>,
    val submit: (email: String, password: String, firstName: String, lastName: String) -> Unit,
    val clearError: () -> Unit,
    val close: () -> Unit,
)

/**
 * An open forgot-password screen and the teardown for it. See [LoginSession].
 *
 * Four actions rather than one submit, because this flow is a conversation with an admin rather
 * than a single request: ask, wait (and re-ask, since the status watch is a socket), finish with
 * their code, and — after a decline — ask a second time without walking back through sign-in.
 */
class ForgotPasswordSession(
    val state: StateFlow<ForgotPasswordUiState>,
    val requestReset: (email: String) -> Unit,
    val completeReset: (code: String, newPassword: String) -> Unit,
    val checkStatus: () -> Unit,
    val retryRequest: () -> Unit,
    val close: () -> Unit,
)

/**
 * An open invite-claim screen and the teardown for it. See [LoginSession].
 *
 * Two actions, not three: the native clients also drive `start(serverUrl, code, remoteUrl)` for a
 * tapped Universal Link, and web never can — see [ClaimInvitePanel] for why a browser already
 * knows its server. Leaving it off the seam means a web screen cannot reach for it by accident.
 */
class ClaimInviteSession(
    val state: StateFlow<ClaimInviteUiState>,
    val lookUp: (code: String) -> Unit,
    val claim: (password: String, firstName: String, lastName: String) -> Unit,
    val close: () -> Unit,
)

/** An open pending-approval screen and the teardown for it. See [LoginSession]. */
class PendingApprovalSession(
    val state: StateFlow<PendingApprovalUiState>,
    val checkStatus: () -> Unit,
    val cancelRegistration: () -> Unit,
    val acknowledgeApproval: () -> Unit,
    val close: () -> Unit,
)

/**
 * The production seam, over the started client graph.
 *
 * Every ViewModel here is a Koin `factory`, so each `open*` yields a fresh one — which is what
 * makes `close()` safe to call on the way out instead of poisoning a shared instance.
 */
fun graphAuth(koin: Koin): AuthGraph =
    object : AuthGraph {
        private val authSession = koin.get<AuthSession>()

        override val authState: StateFlow<AuthState> get() = authSession.authState

        override suspend fun initialize() = authSession.initializeAuthState()

        override suspend fun refreshOpenRegistration() = authSession.refreshOpenRegistration()

        override suspend fun signOut() {
            koin.get<LogoutUseCase>().invoke()
        }

        override fun openLogin(): LoginSession {
            val viewModel = koin.get<LoginViewModel>()
            return LoginSession(
                state = viewModel.state,
                submit = viewModel::onLoginSubmit,
                clearError = viewModel::clearError,
                close = viewModel::close,
            )
        }

        override fun openSetup(): SetupSession {
            val viewModel = koin.get<SetupViewModel>()
            return SetupSession(
                state = viewModel.state,
                submit = viewModel::onSetupSubmit,
                clearError = viewModel::clearError,
                close = viewModel::close,
            )
        }

        override fun openRegister(): RegisterSession {
            val viewModel = koin.get<RegisterViewModel>()
            return RegisterSession(
                state = viewModel.state,
                submit = viewModel::onRegisterSubmit,
                clearError = viewModel::clearError,
                close = viewModel::close,
            )
        }

        override fun openPendingApproval(
            userId: String,
            email: String,
        ): PendingApprovalSession {
            val viewModel = koin.get<PendingApprovalViewModel> { parametersOf(userId, email) }
            return PendingApprovalSession(
                state = viewModel.state,
                checkStatus = viewModel::checkStatus,
                cancelRegistration = viewModel::cancelRegistration,
                acknowledgeApproval = viewModel::acknowledgeApproval,
                close = viewModel::close,
            )
        }

        override fun openForgotPassword(): ForgotPasswordSession {
            val viewModel = koin.get<ForgotPasswordViewModel>()
            return ForgotPasswordSession(
                state = viewModel.state,
                requestReset = viewModel::requestReset,
                completeReset = viewModel::completeReset,
                checkStatus = viewModel::checkStatus,
                retryRequest = viewModel::retryRequest,
                close = viewModel::close,
            )
        }

        override fun openClaimInvite(): ClaimInviteSession {
            val viewModel = koin.get<ClaimInviteViewModel>()
            return ClaimInviteSession(
                state = viewModel.state,
                lookUp = viewModel::onCodeEntered,
                claim = viewModel::onClaimSubmit,
                close = viewModel::close,
            )
        }
    }
