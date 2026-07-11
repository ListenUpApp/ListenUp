package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcAuthRecovery
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.CoroutineScope
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
 *  - [PlaybackManager] / [PlaybackController] — owned by the platform playback module.
 *  - [UserRepository] — owned by `socialModule`.
 *  - [CoroutineScope] — provided by `appCoreModule` under the `appScope` qualifier. Listed so
 *    verify can resolve the named qualifier reference in `CampfireSessionController`.
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
                        PlaybackManager::class,
                        PlaybackController::class,
                        UserRepository::class,
                        CoroutineScope::class,
                    ),
            )
        }
    })
