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
 *  - [PushPlatform] and [PushTokenProvider] — owned by the Android and iOS platform modules.
 *    Both are resolved nullable in [pushClientModule] via `getOrNull()`, but both are still
 *    declared here because `verify()`'s static analysis needs every referenced type resolvable,
 *    nullable or not — the nullability is a runtime distinction, not a static-graph one.
 *
 *    ⛔ **And that gap is exactly why this test cannot be the whole story.** `verify()` proves the
 *    module's references are satisfiable *somewhere*; it says nothing about whether the graph a
 *    given platform actually assembles can build them. `PushPlatform` was a hard `get()` here and
 *    this test stayed green, while web and desktop — which have no value to bind — threw on every
 *    resolution. The counterpart gate is `probeRuntimeEntryPoints`, which resolves the real
 *    browser graph. `verify()` is also JVM-only, so it can never cover Kotlin/JS by itself.
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
