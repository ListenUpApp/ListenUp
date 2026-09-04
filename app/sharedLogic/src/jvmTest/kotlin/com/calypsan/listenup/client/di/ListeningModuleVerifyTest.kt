package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.data.local.db.TentativeSpanDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.domain.repository.AuthSession
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [listeningModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies
 * the listening bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ListeningEventDao] — owned by `persistenceModule`.
 *  - [GenreDao] — owned by `persistenceModule`.
 *  - [PlaybackPositionDao] — owned by `persistenceModule`.
 *  - [TransactionRunner] — owned by `persistenceModule`.
 *  - [AuthSession] — owned by `clientAuthModule`.
 *  - [String] (named `"deviceId"`) — owned by the platform playback modules.
 *  - [PendingOperationQueue] — owned by `clientSyncModule`.
 *  - [TentativeSpanDao] — owned by `persistenceModule` (pulled in by the recorder binding).
 *  - [DeviceInfoProvider] — owned by the platform playback modules (recorder binding).
 *
 * The recorder binding also injects suspend lambdas (`enqueue` / `currentUserId`);
 * `verify()` sees their erased function types, hence the [Function0]/[Function1]/[Function6] entries.
 */
@OptIn(KoinExperimentalAPI::class)
class ListeningModuleVerifyTest :
    FunSpec({

        test("listeningModule wires up against its declared external dependencies") {
            listeningModule.verify(
                extraTypes =
                    listOf(
                        ListeningEventDao::class,
                        TentativeSpanDao::class,
                        GenreDao::class,
                        PlaybackPositionDao::class,
                        TransactionRunner::class,
                        AuthSession::class,
                        DeviceInfoProvider::class,
                        String::class,
                        PendingOperationQueue::class,
                        ApiClientFactory::class,
                        // Recorder injects suspend lambdas (enqueue/currentUserId);
                        // verify() sees their erased function types.
                        Function0::class,
                        Function1::class,
                        Function6::class,
                    ),
            )
        }
    })
