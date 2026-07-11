package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcAuthRecovery
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [campfireClientModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies the
 * campfire bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [RpcAuthRecovery] — owned by `networkModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class CampfireClientModuleVerifyTest :
    FunSpec({

        test("campfireClientModule wires up against its declared external dependencies") {
            campfireClientModule.verify(
                extraTypes =
                    listOf(
                        ApiClientFactory::class,
                        ServerConfig::class,
                        RpcAuthRecovery::class,
                    ),
            )
        }
    })
