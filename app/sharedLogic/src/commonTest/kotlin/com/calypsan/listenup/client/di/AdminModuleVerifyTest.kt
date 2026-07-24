package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [adminModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies
 * the admin bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [ErrorBus] — owned by `appCoreModule`.
 *  - The authed `InviteService` RPC channel — owned by `clientAuthModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class AdminModuleVerifyTest :
    FunSpec({

        test("adminModule wires up against its declared external dependencies") {
            adminModule.verify(
                extraTypes =
                    listOf(
                        ErrorBus::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                    ),
            )
        }
    })
