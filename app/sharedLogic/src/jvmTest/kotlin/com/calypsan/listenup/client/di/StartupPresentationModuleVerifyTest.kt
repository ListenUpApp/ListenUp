package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [startupPresentationModule]. One binding, and it decides which screen the app
 * opens on — a missing dependency here fails before any UI exists to report it.
 *
 * The whitelist enumerates dependencies this module pulls in but other modules own:
 *
 *  - [UserRepository] — owned by `socialModule`.
 *  - [RpcChannel] — constructed inline by `rpcChannel<LibraryAdminService>()`, not resolved by Koin.
 *  - [AuthSession] — owned by `authModule`.
 *  - [SyncRepository] — owned by `clientSyncModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class StartupPresentationModuleVerifyTest :
    FunSpec({

        test("startupPresentationModule wires up against its declared external dependencies") {
            startupPresentationModule.verify(
                extraTypes =
                    listOf(
                        UserRepository::class,
                        RpcChannel::class,
                        AuthSession::class,
                        SyncRepository::class,
                    ),
            )
        }
    })
