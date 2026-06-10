package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.UserStatsDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
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
 *  - [UserStatsDao] — owned by `persistenceModule`.
 *  - [GenreDao] — owned by `persistenceModule`.
 *  - [PlaybackPositionDao] — owned by `persistenceModule`.
 *  - [TransactionRunner] — owned by `persistenceModule`.
 *  - [AuthSession] — owned by `clientAuthModule`.
 *  - [String] (named `"deviceId"`) — owned by the platform playback modules.
 *  - [PendingOperationQueue] — owned by `clientSyncRenovationModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class ListeningModuleVerifyTest :
    FunSpec({

        test("listeningModule wires up against its declared external dependencies") {
            listeningModule.verify(
                extraTypes =
                    listOf(
                        ListeningEventDao::class,
                        UserStatsDao::class,
                        GenreDao::class,
                        PlaybackPositionDao::class,
                        TransactionRunner::class,
                        AuthSession::class,
                        String::class,
                        PendingOperationQueue::class,
                        ApiClientFactory::class,
                    ),
            )
        }
    })
