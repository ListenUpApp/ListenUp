package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordViewModel
import com.calypsan.listenup.client.presentation.auth.LoginViewModel
import com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel
import com.calypsan.listenup.client.presentation.auth.RegisterViewModel
import com.calypsan.listenup.client.presentation.auth.SetupViewModel
import com.calypsan.listenup.client.presentation.connect.ServerConnectViewModel
import com.calypsan.listenup.client.presentation.connect.ServerSelectViewModel
import com.calypsan.listenup.client.presentation.invite.ClaimInviteViewModel
import com.calypsan.listenup.client.presentation.setup.LibrarySetupViewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Koin qualifier for the application-lifetime [kotlinx.coroutines.CoroutineScope] (`appCoreModule`). */
private const val APP_SCOPE = "appScope"

/**
 * Auth and connection ViewModels.
 */
internal val authPresentationModule =
    module {
        factory {
            ServerSelectViewModel(
                serverRepository = get(),
                adoptServer = get(),
                instanceRepository = get(),
                localNetworkAccess = get(),
                errorBus = get(),
                // App-lifetime scope: selecting a server flips the global auth state, which tears this
                // screen (and its viewModelScope) down mid-activation. The activation must outlive it.
                appScope = get(qualifier = named(APP_SCOPE)),
            )
        }
        factory {
            ServerConnectViewModel(
                adoptServer = get(),
                instanceRepository = get(),
                localNetworkAccess = get(),
                // App-lifetime scope: saving the verified URL flips the global auth state, tearing this
                // screen (and its viewModelScope) down mid-activation — the work must outlive it.
                appScope = get(qualifier = named(APP_SCOPE)),
            )
        }
        factory {
            SetupViewModel(
                setupUseCase = get(),
                authSession = get(),
            )
        }
        factory {
            LoginViewModel(
                loginUseCase = get(),
            )
        }
        factory {
            RegisterViewModel(
                registerUseCase = get(),
            )
        }
        // PendingApprovalViewModel - takes (userId, email) parameters
        factory { params ->
            PendingApprovalViewModel(
                authSession = get(),
                registrationStatusStream = get(),
                userId = params.get<String>(0),
                email = params.get<String>(1),
                pushRegistrar = get(),
            )
        }
        factory {
            ForgotPasswordViewModel(
                repository = get(),
                errorBus = get(),
            )
        }
        factory {
            ClaimInviteViewModel(
                repository = get(),
                serverConfig = get(),
                instanceRepository = get(),
                authSession = get(),
                adoptServer = get(),
            )
        }
        // LibrarySetupViewModel for initial library configuration
        factory {
            LibrarySetupViewModel(
                libraryAdminChannel = rpcChannel<LibraryAdminService>(),
                errorBus = get(),
                // App-lifetime scope: the initial scan triggered after onboarding adds its
                // folders must outlive this wizard (torn down when onboarding finishes and we
                // navigate to the Shell), so it can't ride viewModelScope.
                appScope = get(qualifier = named(APP_SCOPE)),
            )
        }
    }
