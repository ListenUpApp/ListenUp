package com.calypsan.listenup.client.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.features.connect.ServerSelectScreen
import com.calypsan.listenup.client.features.connect.ServerSetupScreen
import com.calypsan.listenup.client.features.invite.JoinScreen
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordViewModel
import com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.calypsan.listenup.api.result.AppResult

/**
 * Navigation for pending approval screen.
 *
 * Shows the pending approval screen for users who have registered
 * but are waiting for admin approval. Handles:
 * - RPC watch for real-time approval notification
 * - Auto-login on approval
 * - Cancel to return to login
 */
@Composable
internal fun AndroidPendingApprovalNavigation(
    userId: String,
    email: String,
) {
    val viewModel: PendingApprovalViewModel =
        koinViewModel {
            org.koin.core.parameter
                .parametersOf(userId, email)
        }

    com.calypsan.listenup.client.features.auth.PendingApprovalScreen(
        viewModel = viewModel,
        onNavigateToLogin = {
            // Auth state automatically updates after clearPendingRegistration.
        },
    )
}

/**
 * Navigation for the invite-claim flow.
 *
 * Shows [JoinScreen] and handles completion/cancellation. The RPC invite vertical
 * reads the server URL from [ServerConfig]; the deep link carries its own URL, so
 * [JoinScreen] hands both URL and code to the ViewModel's `start`, which
 * persists the URL before the lookup runs (one coroutine, deterministic order —
 * no race on a fresh install). On a successful claim the repository persists the
 * issued session (AuthState → Authenticated); once [JoinScreen] reports the claim
 * via `onClaimed`, the invite is consumed and auth-state routing takes over.
 */
@Composable
internal fun JoinNavigation(
    serverUrl: String,
    inviteCode: String,
    remoteUrl: String?,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
) {
    JoinScreen(
        onClaimed = onComplete,
        onCancel = onCancel,
        initialCode = inviteCode,
        serverUrl = serverUrl,
        remoteUrl = remoteUrl,
    )
}

/**
 * Loading screen shown during auth state initialization.
 * Displayed briefly on app start while checking for stored credentials.
 */
@Composable
internal fun LoadingScreen() {
    FullScreenLoadingIndicator()
}

/**
 * Server setup navigation - shown when no server URL is configured.
 *
 * Flow:
 * 1. ServerSelectScreen - shows discovered servers + manual option
 * 2. ServerSetupScreen - manual URL entry (if user clicks "Add Manually")
 *
 * After successful selection/verification, AuthState changes trigger automatic navigation.
 */
@Composable
internal fun AndroidServerSetupNavigation() {
    val backStack = rememberNavBackStack(ServerSelect)

    NavDisplay(
        backStack = backStack,
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        onBack = {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        },
        entryProvider =
            entryProvider {
                entry<ServerSelect> {
                    ServerSelectScreen(
                        onServerActivated = {
                            // Server is selected, AuthState will change automatically
                        },
                        onManualEntryRequested = {
                            backStack.add(ServerSetup)
                        },
                    )
                }
                entry<ServerSetup> {
                    ServerSetupScreen(
                        onServerVerified = {
                            // URL is saved, AuthState will change automatically
                            // Pop back to select; the auth screen takes over once AuthState updates
                        },
                        onBack = {
                            backStack.popFrom(ServerSetup)
                        },
                    )
                }
            },
    )
}

/**
 * Setup navigation - shown when server needs initial root user.
 * After successful setup, AuthState.Authenticated triggers automatic navigation.
 */
@Composable
internal fun AndroidSetupNavigation() {
    val backStack = rememberNavBackStack(Setup)

    NavDisplay(
        backStack = backStack,
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        entryProvider =
            entryProvider {
                entry<Setup> {
                    com.calypsan.listenup.client.features.auth
                        .SetupScreen()
                }
            },
    )
}

/**
 * Login navigation - shown when server is configured but user needs to authenticate.
 * After successful login, AuthState.Authenticated triggers automatic navigation.
 *
 * @param openRegistration Whether the server allows public registration
 */
@Composable
internal fun AndroidLoginNavigation(
    authSession: AuthSession,
    openRegistration: Boolean,
) {
    val scope = rememberCoroutineScope()
    val backStack = rememberNavBackStack(Login)
    val serverConfig: com.calypsan.listenup.client.domain.repository.ServerConfig = koinInject()
    val instanceRepository: com.calypsan.listenup.client.domain.repository.InstanceRepository = koinInject()
    var rootResetArmed by remember { mutableStateOf(false) }

    // Refresh open registration value from server
    // This ensures the "Create Account" link appears if admin enabled it
    LaunchedEffect(Unit) {
        authSession.refreshOpenRegistration()
        // Live probe, never cached: the root-reset hatch is a 15-minute window armed by a server
        // restart, so only a fresh answer is meaningful. On failure the entry simply stays hidden.
        rootResetArmed =
            (instanceRepository.getServerInfo(forceRefresh = true) as? AppResult.Success)
                ?.run { data.rootResetArmed } == true
    }

    NavDisplay(
        backStack = backStack,
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        // Guarded like AuthNavigation's: popping the last entry would empty the stack and leave
        // NavDisplay in an undefined state (a frozen composition over cleared ViewModel stores).
        onBack = {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        },
        entryProvider =
            entryProvider {
                entry<Login> {
                    com.calypsan.listenup.client.features.auth
                        .LoginScreen(
                            openRegistration = openRegistration,
                            onChangeServer = {
                                scope.launch {
                                    // Clear server URL to go back to server selection
                                    serverConfig.disconnectFromServer()
                                }
                            },
                            onRegister = {
                                backStack.add(Register)
                            },
                            onForgotPassword = {
                                backStack.add(ForgotPassword)
                            },
                            onClaimInvite = {
                                backStack.add(ClaimInvite)
                            },
                            showRootResetEntry = rootResetArmed,
                        )
                }
                entry<ClaimInvite> {
                    // The code is looked up against the server already connected. A successful
                    // claim stores the session, and auth-state routing takes over — the same hand-off
                    // as the deep-link path, minus consuming a link there is none of here.
                    JoinScreen(
                        onClaimed = {},
                        onCancel = {
                            backStack.popFrom(ClaimInvite)
                        },
                    )
                }
                entry<Register> {
                    com.calypsan.listenup.client.features.auth
                        .RegisterScreen(
                            onBackClick = {
                                backStack.popFrom(Register)
                            },
                        )
                }
                entry<ForgotPassword> {
                    val viewModel: ForgotPasswordViewModel = koinViewModel()
                    // Complete and Denied both render "Back to Sign In" through onBack — that is
                    // what returns the user to Login instead of dead-ending on a terminal message.
                    com.calypsan.listenup.client.features.auth
                        .ForgotPasswordScreen(
                            viewModel = viewModel,
                            onBack = {
                                backStack.popFrom(ForgotPassword)
                            },
                        )
                }
            },
    )
}

/**
 * Whether the shell-level connection banner is drawn over [top]. Not over Login: its "Sign in"
 * would point at the page already open, and the banner, pinned above the navigation stack, reads
 * as floating loose while Login slides in beneath it. (iOS presents sign-in as a sheet over its
 * banner; web signs in inline — neither shows the banner over a login page.)
 */
internal fun showsConnectionBannerOver(top: NavKey?): Boolean = top != Login
