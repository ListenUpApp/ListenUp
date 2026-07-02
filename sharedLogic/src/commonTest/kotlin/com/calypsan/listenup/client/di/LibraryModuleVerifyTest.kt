package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.dao.LibraryDao
import com.calypsan.listenup.client.data.local.db.dao.LibraryFolderDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.sync.FtsPopulatorContract
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.LibrarySync
import com.calypsan.listenup.client.playback.ListeningEventRecorder
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.CoroutineScope
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [libraryModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies
 * the library bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [SyncEngine] — owned by `clientSyncModule`.
 *  - [SyncEngineState] — owned by `clientSyncModule`.
 *  - [OfflineEditor] — owned by `clientSyncModule`.
 *  - [PendingOperationQueue] — owned by `clientSyncModule`.
 *  - [AuthSession] — owned by `clientAuthModule`.
 *  - [ListeningEventRecorder] — owned by `listeningModule`.
 *  - [BookDao] — owned by `persistenceModule`.
 *  - [ListeningEventDao] — owned by `persistenceModule`.
 *  - [PlaybackPositionDao] — owned by `persistenceModule`.
 *  - [LibraryDao] — owned by `persistenceModule`.
 *  - [LibraryFolderDao] — owned by `persistenceModule`.
 *  - [CoroutineScope] (named `"appScope"`) — owned by `appCoreModule`.
 *  - [BookRepository] — owned by `bookModule`.
 *  - [ListenUpDatabase] — owned by `platformDatabaseModule`.
 *  - [TransactionRunner] — owned by `persistenceModule`.
 *  - [LibrarySync] — owned by `settingsModule`.
 *  - [FtsPopulatorContract] — owned by `searchModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class LibraryModuleVerifyTest :
    FunSpec({

        test("libraryModule wires up against its declared external dependencies") {
            libraryModule.verify(
                extraTypes =
                    listOf(
                        SyncEngine::class,
                        SyncEngineState::class,
                        OfflineEditor::class,
                        PendingOperationQueue::class,
                        AuthSession::class,
                        ListeningEventRecorder::class,
                        BookDao::class,
                        ListeningEventDao::class,
                        PlaybackPositionDao::class,
                        CoroutineScope::class,
                        BookRepository::class,
                        ListenUpDatabase::class,
                        TransactionRunner::class,
                        LibrarySync::class,
                        ApiClientFactory::class,
                        LibraryDao::class,
                        LibraryFolderDao::class,
                        FtsPopulatorContract::class,
                    ),
            )
        }
    })
