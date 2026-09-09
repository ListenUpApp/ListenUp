package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.push.PushRegistrar
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.InviteRepository
import com.calypsan.listenup.client.domain.repository.PasswordResetRepository
import com.calypsan.listenup.client.domain.repository.RegistrationStatusStream
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.ServerRepository
import com.calypsan.listenup.client.domain.usecase.auth.LoginUseCase
import com.calypsan.listenup.client.domain.usecase.auth.RegisterUseCase
import com.calypsan.listenup.client.domain.usecase.auth.SetupUseCase
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.CoroutineScope
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [authPresentationModule] — the largest presentation module (9 ViewModel
 * bindings), and the one a missing binding hurts most: every one of its screens sits on the
 * pre-login path, where a runtime DI crash locks the reader out of the app entirely.
 *
 * The whitelist enumerates dependencies [authPresentationModule] pulls in but other modules own:
 *
 *  - [ServerRepository] — owned by `connectionModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [InstanceRepository] — owned by `connectionModule`.
 *  - [ErrorBus] — owned by `appCoreModule`.
 *  - [CoroutineScope] — the app-lifetime scope, owned by `appCoreModule` (qualifier `appScope`).
 *  - [SetupUseCase] — owned by `authModule`.
 *  - [AuthSession] — owned by `authModule`.
 *  - [LoginUseCase] — owned by `authModule`.
 *  - [RegisterUseCase] — owned by `authModule`.
 *  - [RegistrationStatusStream] — owned by `authModule`.
 *  - [PushRegistrar] — owned by the platform push module (nullable, defaulted).
 *  - [PasswordResetRepository] — owned by `authModule`.
 *  - [InviteRepository] — owned by `adminModule`.
 *  - [RpcChannel] — constructed inline by `rpcChannel<LibraryAdminService>()`, not resolved by Koin.
 *  - [String] — `PendingApprovalViewModel` takes `userId`/`email` as runtime `parametersOf` args.
 */
@OptIn(KoinExperimentalAPI::class)
class AuthPresentationModuleVerifyTest :
    FunSpec({

        test("authPresentationModule wires up against its declared external dependencies") {
            authPresentationModule.verify(
                extraTypes =
                    listOf(
                        ServerRepository::class,
                        ServerConfig::class,
                        InstanceRepository::class,
                        ErrorBus::class,
                        CoroutineScope::class,
                        SetupUseCase::class,
                        AuthSession::class,
                        LoginUseCase::class,
                        RegisterUseCase::class,
                        RegistrationStatusStream::class,
                        PushRegistrar::class,
                        PasswordResetRepository::class,
                        InviteRepository::class,
                        RpcChannel::class,
                        String::class,
                    ),
            )
        }
    })
