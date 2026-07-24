package com.calypsan.listenup.client.navigation

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.features.auth.LoginScreen
import com.calypsan.listenup.client.features.auth.PendingApprovalScreen
import com.calypsan.listenup.client.features.auth.RegisterScreen
import com.calypsan.listenup.client.features.auth.SetupScreen
import com.calypsan.listenup.client.features.connect.ServerSelectScreen
import com.calypsan.listenup.client.features.connect.ServerSetupScreen
import com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_checking_server

/**
 * [SavedStateConfiguration] that registers every [AuthRoute] subtype for `NavKey`
 * polymorphic serialization. The cross-platform [rememberNavBackStack] overload
 * requires an explicit [SerializersModule] (the no-config Android-only overload
 * uses reflection, which isn't portable to Desktop / iOS — see
 * `RememberNavBackStack.android.kt`'s docs). Closed sealed hierarchies still
 * need every subtype enumerated here so the polymorphic discriminator survives
 * a process-death save→restore round trip.
 */
private val authNavSavedStateConfiguration =
    SavedStateConfiguration {
        serializersModule =
            SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(ServerSelect::class)
                    subclass(ServerSetup::class)
                    subclass(Setup::class)
                    subclass(Login::class)
                    subclass(Register::class)
                }
            }
    }

/**
 * Auth-only navigation for initial authentication flow.
 *
 * This handles:
 * 1. Server setup (when no server URL configured)
 * 2. Root user setup (when server has no users)
 * 3. Login (when server is configured)
 * 4. Registration (when open registration is enabled)
 * 5. Pending approval (after registration)
 *
 * After successful authentication, calls [onAuthenticated] to proceed to main app.
 */
@Composable
fun AuthNavigation(
    onAuthenticated: () -> Unit,
    authSession: AuthSession = koinInject(),
) {
    val scope = rememberCoroutineScope()

    // Initialize auth state on first composition
    LaunchedEffect(Unit) {
        scope.launch {
            authSession.initializeAuthState()
        }
    }

    // Observe auth state changes
    val authState by authSession.authState.collectAsStateWithLifecycle()

    // Route to appropriate screen based on auth state
    val currentAuthState = authState
    when (currentAuthState) {
        AuthState.Initializing -> {
            // Show blank screen while determining auth state
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
            )
        }

        AuthState.NeedsServerUrl -> {
            ServerSetupNavigation()
        }

        AuthState.CheckingServer -> {
            FullScreenLoadingIndicator(message = stringResource(Res.string.common_checking_server))
        }

        AuthState.NeedsSetup -> {
            SetupNavigation()
        }

        is AuthState.NeedsLogin -> {
            LoginNavigation(authSession, currentAuthState.openRegistration)
        }

        is AuthState.PendingApproval -> {
            PendingApprovalNavigation(
                userId = currentAuthState.userId.value,
                email = currentAuthState.email,
            )
        }

        is AuthState.Authenticated, is AuthState.SessionLapsed -> {
            // Desktop treats a lapsed session like the authenticated shell (M2/M3); its
            // dedicated re-auth affordance follows in a later phase.
            LaunchedEffect(Unit) {
                onAuthenticated()
            }
        }
    }
}

/**
 * Navigation for pending approval screen.
 */
@Composable
private fun PendingApprovalNavigation(
    userId: String,
    email: String,
) {
    val viewModel: PendingApprovalViewModel =
        koinViewModel {
            org.koin.core.parameter
                .parametersOf(userId, email)
        }

    PendingApprovalScreen(
        viewModel = viewModel,
        onNavigateToLogin = {
            // Auth state automatically updates after clearPendingRegistration.
        },
    )
}

/**
 * Server setup navigation - shown when no server URL is configured.
 *
 * Shows server discovery via mDNS with option for manual URL entry.
 */
@Composable
private fun ServerSetupNavigation() {
    val backStack = rememberNavBackStack(authNavSavedStateConfiguration, ServerSelect)

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
        predictivePopTransitionSpec = {
            slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
        },
        entryProvider =
            entryProvider {
                entry<ServerSelect> {
                    ServerSelectScreen(
                        onServerActivated = {
                            // Server is saved, AuthState will change automatically
                        },
                        onManualEntryRequested = {
                            backStack.add(ServerSetup)
                        },
                    )
                }
                entry<ServerSetup> {
                    ServerSetupScreen(
                        onServerVerified = {
                            // Server is saved, AuthState will change automatically
                        },
                        onBack = {
                            backStack.removeAt(backStack.lastIndex)
                        },
                    )
                }
            },
    )
}

/**
 * Setup navigation - shown when server needs initial root user.
 */
@Composable
private fun SetupNavigation() {
    val backStack = rememberNavBackStack(authNavSavedStateConfiguration, Setup)

    NavDisplay(
        backStack = backStack,
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        predictivePopTransitionSpec = {
            slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
        },
        entryProvider =
            entryProvider {
                entry<Setup> {
                    SetupScreen()
                }
            },
    )
}

/**
 * Login navigation - shown when server is configured but user needs to authenticate.
 */
@Composable
private fun LoginNavigation(
    authSession: AuthSession,
    openRegistration: Boolean,
) {
    val scope = rememberCoroutineScope()
    val backStack = rememberNavBackStack(authNavSavedStateConfiguration, Login)
    val serverConfig: com.calypsan.listenup.client.domain.repository.ServerConfig = koinInject()

    // Refresh open registration value from server
    LaunchedEffect(Unit) {
        authSession.refreshOpenRegistration()
    }

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
        predictivePopTransitionSpec = {
            slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
        },
        entryProvider =
            entryProvider {
                entry<Login> {
                    LoginScreen(
                        openRegistration = openRegistration,
                        onChangeServer = {
                            scope.launch {
                                serverConfig.disconnectFromServer()
                            }
                        },
                        onRegister = {
                            backStack.add(Register)
                        },
                    )
                }
                entry<Register> {
                    RegisterScreen(
                        onBackClick = {
                            backStack.removeAt(backStack.lastIndex)
                        },
                    )
                }
            },
    )
}
