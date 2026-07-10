package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.client.data.push.PushTokenProvider
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcAuthRecovery
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [pushClientModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies
 * the push bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [RpcAuthRecovery] — owned by `networkModule`.
 *  - [InstanceRepository] — owned by `settingsModule`.
 *  - [PushPlatform] — owned by the Android platform module (`androidModule`).
 *  - [PushTokenProvider] — owned by the Android platform module. Resolved
 *    nullable in [pushClientModule] via `getOrNull()`, but still declared here
 *    because `verify()`'s static analysis needs every referenced type
 *    resolvable, nullable or not — the nullability is a runtime distinction,
 *    not a static-graph one.
 */
@OptIn(KoinExperimentalAPI::class)
class PushClientModuleVerifyTest :
    FunSpec({

        test("pushClientModule wires up against its declared external dependencies") {
            pushClientModule.verify(
                extraTypes =
                    listOf(
                        ApiClientFactory::class,
                        ServerConfig::class,
                        RpcAuthRecovery::class,
                        InstanceRepository::class,
                        PushPlatform::class,
                        PushTokenProvider::class,
                    ),
            )
        }
    })
