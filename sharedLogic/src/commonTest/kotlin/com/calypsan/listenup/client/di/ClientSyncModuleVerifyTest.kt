package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.documents.DocumentStorage
import com.calypsan.listenup.client.data.connection.ConnectionCoordinator
import com.calypsan.listenup.client.data.connection.ConnectionEvidence
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.sync.domains.MirroredDomain
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.AvatarDownloadRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.version.ClientIdentity
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [clientSyncModule]. Per the architecture rubric every leaf
 * Koin module is covered by a `module.verify()` test in commonTest — this is the
 * sync engine module's. The whitelist enumerates dependencies the
 * module's bindings pull in but other modules own:
 *
 *  - [ListenUpDatabase] — owned by `platformDatabaseModule` (provides DAOs).
 *  - [TransactionRunner] — owned by `repositoryModule` (bound to `RoomTransactionRunner`).
 *    The composed handlers route all multi-DAO writes through this seam.
 *  - [ApiClientFactory] — owned by `networkModule` (the bearer-equipped HttpClient
 *    factory; SSE + catch-up clients use it).
 *  - [ServerConfig] — owned by `dataModule` (segregated interface bound to
 *    SettingsRepositoryImpl). SSE + catch-up clients use it for current-URL reads.
 *  - [CoroutineScope] (named `appScope`) — owned by `appCoreModule`.
 *  - [Function1], [Function2], [Function3] — Koin's verify treats constructor lambda params
 *    (`DomainDigestClient`'s `httpClientProvider`/`serverUrlProvider` are `suspend () -> X` =
 *    Function1; `SyncEventDispatcher.onCursorStale` is `suspend (Long?) -> Unit` =
 *    Function2; `cursorAdvance` is `suspend (String, Long) -> Unit` = Function3)
 *    as resolvable deps. They're satisfied at construction time by the
 *    module's `single { }` block.
 *  - [Boolean] (named `"playbackAvailable"`) — platform-provided: `true` on Android/iOS,
 *    `false` on Desktop. Declared here so `verify()` does not fail on the qualified lookup.
 *  - [AuthSession] — owned by `dataModule` (bound to SettingsRepositoryImpl). The listening-event
 *    sync handler reads the signed-in user id from it to stamp synced cross-device events.
 *  - [ConnectionCoordinator] — owned by the main `Koin.kt` module; [ReconnectionSupervisor]
 *    drives `reevaluate` through it to re-point the active URL during an outage.
 *  - [ClientIdentity] — owned by `appCoreModule` (interim stub binding). Consumed by the
 *    `ConnectionHealthStore` single declared here.
 *  - [LocalPreferences] — owned by `SettingsModule` (bound to `SettingsRepositoryImpl`).
 *    Consumed by the `ConnectionHealthStore` single declared here.
 *  - [StateFlow] — Koin's verify resolves `ConnectionHealthStore`'s required `authStateFlow:
 *    StateFlow<AuthState>` constructor param against the erased raw type (no default value to
 *    fall back on, unlike `SyncEngine`'s optional `authState` param). The single itself passes
 *    the real `get<AuthSession>().authState` at construction, not a Koin-resolved `StateFlow`.
 *  - [InstanceRepository] — owned by the main `Koin.kt` module; [ReconnectionSupervisor] probes
 *    the resolved URL with its unauthenticated `verifyServer` to detect instance identity changes.
 *  - [ErrorBus] — owned by the main `Koin.kt` module; [ReconnectionSupervisor] surfaces an
 *    instance-changed signal through it.
 *  - [MirroredDomain] — Koin's verify special-cases a `List<T>` constructor parameter by
 *    checking the list's element type against the definition index; [SyncDomainCatalog]'s
 *    `mirrored: List<MirroredDomain<*>>` is populated by literal `listOf(...)` construction
 *    inside the module, not by individual `single<MirroredDomain<*>>` bindings, so the
 *    element type is declared here instead.
 */
@OptIn(KoinExperimentalAPI::class)
class ClientSyncModuleVerifyTest :
    FunSpec({

        test("clientSyncModule wires up against its declared external dependencies") {
            clientSyncModule.verify(
                extraTypes =
                    listOf(
                        ListenUpDatabase::class,
                        TransactionRunner::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                        CoroutineScope::class,
                        AuthSession::class,
                        AvatarDownloadRepository::class,
                        ConnectionCoordinator::class,
                        ConnectionEvidence::class,
                        ClientIdentity::class,
                        LocalPreferences::class,
                        NetworkMonitor::class,
                        StateFlow::class,
                        InstanceRepository::class,
                        ErrorBus::class,
                        Function1::class,
                        Function2::class,
                        Function3::class,
                        Boolean::class,
                        ImageStorage::class,
                        DocumentStorage::class,
                        MirroredDomain::class,
                        com.calypsan.listenup.client.data.remote.RpcAuthRecovery::class,
                    ),
            )
        }
    })
