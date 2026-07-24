package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.SecureStorage
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.CoroutineScope
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [connectionModule]. Per the architecture rubric every leaf
 * Koin module is covered by a `module.verify()` test in commonTest.
 *
 * The whitelist enumerates dependencies the connection bindings pull in but
 * other modules own:
 *
 *  - [SecureStorage] — owned by `platformStorageModule`. Read directly by the
 *    `InstanceRepository` binding to avoid a circular dep via `SettingsRepository`.
 *  - [ServerConfig] — owned by `settingsModule`. Used by `ConnectionCoordinator`
 *    to observe URL changes and by `InstanceRepository` to read the current URL.
 *  - [NetworkMonitor] — owned by `platformDeviceModule`. `ConnectionCoordinator`
 *    listens for network-regain events to re-evaluate the active connection.
 *  - [ServerDiscoveryService] — owned by `platformDiscoveryModule`. Both
 *    `ServerRepository` and `ConnectionCoordinator` depend on it.
 *  - [CoroutineScope] — provided by `appCoreModule` under the `appScope` qualifier.
 *    Listed so verify can resolve the named qualifier reference in `ConnectionCoordinator`.
 *  - [com.calypsan.listenup.client.data.remote.RemoteCache] (via `getAll()`) — every
 *    `RemoteCache` binding is registered across multiple modules; `getAll()` in
 *    `RpcCacheInvalidator` collects them all at runtime without an explicit whitelist here.
 *  - [LocalPreferences] — owned by `settingsModule`. `InstanceRepository`'s `persistPeerVersion`
 *    seam persists the peer server's version from the pre-auth `ServerInfo` probe there.
 */
@OptIn(KoinExperimentalAPI::class)
class ConnectionModuleVerifyTest :
    FunSpec({

        test("connectionModule wires up against its declared external dependencies") {
            connectionModule.verify(
                extraTypes =
                    listOf(
                        SecureStorage::class,
                        ServerConfig::class,
                        NetworkMonitor::class,
                        ServerDiscoveryService::class,
                        CoroutineScope::class,
                        LocalPreferences::class,
                    ),
            )
        }
    })
