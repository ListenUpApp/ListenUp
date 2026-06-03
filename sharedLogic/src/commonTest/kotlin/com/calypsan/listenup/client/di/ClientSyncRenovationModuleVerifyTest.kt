package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.CoroutineScope
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [clientSyncRenovationModule]. Per the architecture rubric every leaf
 * Koin module is covered by a `module.verify()` test in commonTest — this is the
 * renovated sync engine module's. The whitelist enumerates dependencies the
 * module's bindings pull in but other modules own:
 *
 *  - [ListenUpDatabase] — owned by `platformDatabaseModule` (provides DAOs).
 *  - [TransactionRunner] — owned by `repositoryModule` (bound to `RoomTransactionRunner`).
 *    [BookSyncDomainHandler] routes all multi-DAO writes through this seam.
 *  - [ApiClientFactory] — owned by `networkModule` (the bearer-equipped HttpClient
 *    factory; SSE + catch-up clients use it).
 *  - [ServerConfig] — owned by `dataModule` (segregated interface bound to
 *    SettingsRepositoryImpl). SSE + catch-up clients use it for current-URL reads.
 *  - [CoroutineScope] (named `appScope`) — owned by `syncModule` until D2 cutover.
 *  - [DownloadRepository] — owned by `repositoryModule` (bound to `DownloadRepositoryImpl`).
 *    [SyncEngine] calls [DownloadRepository.recheckWaitingForServer] on every SSE reconnect.
 *  - [Function1], [Function2], [Function3] — Koin's verify treats constructor lambda params
 *    (`DomainDigestClient`'s `httpClientProvider`/`serverUrlProvider` are `suspend () -> X` =
 *    Function1; `SyncEventDispatcher.onCursorStale` is `suspend (Long?) -> Unit` =
 *    Function2; `cursorAdvance` is `suspend (String, Long) -> Unit` = Function3)
 *    as resolvable deps. They're satisfied at construction time by the
 *    module's `single { }` block.
 *  - [Boolean] (named `"playbackAvailable"`) — platform-provided: `true` on Android/iOS,
 *    `false` on Desktop. Declared here so `verify()` does not fail on the qualified lookup.
 */
@OptIn(KoinExperimentalAPI::class)
class ClientSyncRenovationModuleVerifyTest :
    FunSpec({

        test("clientSyncRenovationModule wires up against its declared external dependencies") {
            clientSyncRenovationModule.verify(
                extraTypes =
                    listOf(
                        ListenUpDatabase::class,
                        TransactionRunner::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                        DownloadRepository::class,
                        CoroutineScope::class,
                        Function1::class,
                        Function2::class,
                        Function3::class,
                        Boolean::class,
                    ),
            )
        }
    })
