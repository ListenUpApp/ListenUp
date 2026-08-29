package com.calypsan.listenup.web.features.auth

import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordUiState
import com.calypsan.listenup.client.presentation.auth.LoginUiState
import com.calypsan.listenup.client.presentation.auth.PendingApprovalUiState
import com.calypsan.listenup.client.presentation.auth.RegisterUiState
import com.calypsan.listenup.client.presentation.auth.SetupUiState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An [AuthGraph] with in-memory state — a fake, not a mock, so specs assert on what it recorded
 * rather than on which calls were made.
 *
 * Push a new value into [state] to drive the gate through a transition the way the real
 * `AuthSession` would.
 */
class FakeAuthGraph(
    initial: AuthState = AuthState.Initializing,
) : AuthGraph {
    val state = MutableStateFlow(initial)

    override val authState = state

    var initializeCalls = 0
        private set
    var refreshOpenRegistrationCalls = 0
        private set
    var signOutCalls = 0
        private set

    /** Closed sessions, by screen name — proof the gate tears a ViewModel down on its way out. */
    val closed = mutableListOf<String>()

    /** The most recent login submission, or null. */
    var loginSubmission: Pair<String, String>? = null
        private set

    /** The most recent setup submission as (first, last, email, password, confirm), or null. */
    var setupSubmission: List<String>? = null
        private set

    /** The most recent register submission as (email, password, first, last), or null. */
    var registerSubmission: List<String>? = null
        private set

    val loginState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val setupState = MutableStateFlow<SetupUiState>(SetupUiState.Idle)
    val registerState = MutableStateFlow<RegisterUiState>(RegisterUiState.Idle)
    val pendingState = MutableStateFlow<PendingApprovalUiState>(PendingApprovalUiState.Waiting)
    val forgotState = MutableStateFlow<ForgotPasswordUiState>(ForgotPasswordUiState.EnterEmail)

    /** The address the most recent reset request was opened for, or null. */
    var resetRequestedFor: String? = null
        private set

    override suspend fun initialize() {
        initializeCalls++
    }

    override suspend fun refreshOpenRegistration() {
        refreshOpenRegistrationCalls++
    }

    override suspend fun signOut() {
        signOutCalls++
    }

    override fun openLogin() =
        LoginSession(
            state = loginState,
            submit = { email, password -> loginSubmission = email to password },
            clearError = { loginState.value = LoginUiState.Idle },
            close = { closed += "login" },
        )

    override fun openSetup() =
        SetupSession(
            state = setupState,
            submit = { first, last, email, password, confirm ->
                setupSubmission = listOf(first, last, email, password, confirm)
            },
            clearError = { setupState.value = SetupUiState.Idle },
            close = { closed += "setup" },
        )

    override fun openRegister() =
        RegisterSession(
            state = registerState,
            submit = { email, password, first, last ->
                registerSubmission = listOf(email, password, first, last)
            },
            clearError = { registerState.value = RegisterUiState.Idle },
            close = { closed += "register" },
        )

    override fun openPendingApproval(
        userId: String,
        email: String,
    ) = PendingApprovalSession(
        state = pendingState,
        checkStatus = {},
        cancelRegistration = {},
        acknowledgeApproval = {},
        close = { closed += "pending" },
    )

    override fun openForgotPassword() =
        ForgotPasswordSession(
            state = forgotState,
            requestReset = { email -> resetRequestedFor = email },
            completeReset = { _, _ -> },
            checkStatus = {},
            retryRequest = {},
            close = { closed += "forgot" },
        )
}
