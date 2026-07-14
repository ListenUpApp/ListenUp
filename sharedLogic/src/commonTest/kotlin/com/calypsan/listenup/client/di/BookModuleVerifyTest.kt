package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.TagRepository
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [bookModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies
 * the book bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [BookDao] — owned by `persistenceModule`.
 *  - [ChapterDao] — owned by `persistenceModule`.
 *  - [AudioFileDao] — owned by `persistenceModule`.
 *  - [SearchDao] — owned by `persistenceModule`.
 *  - [TransactionRunner] — owned by `persistenceModule`.
 *  - [ImageStorage] — owned by the platform storage module.
 *  - [NetworkMonitor] — owned by the platform device module.
 *  - [GenreRepository] — owned by `genreTagModule`.
 *  - [TagRepository] — owned by `genreTagModule`.
 *  - [MoodRepository] — owned by `genreTagModule`.
 *  - the [RpcChannel] for `CollectionService` — owned by `collectionModule`.
 *  - [ImageRepository] — owned by `mediaModule`.
 *  - [ImageStagingRepository] — owned by `mediaModule`.
 *  - [SyncDomainHandler] (books) — owned by `clientSyncModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class BookModuleVerifyTest :
    FunSpec({

        test("bookModule wires up against its declared external dependencies") {
            bookModule.verify(
                extraTypes =
                    listOf(
                        BookDao::class,
                        ChapterDao::class,
                        AudioFileDao::class,
                        SearchDao::class,
                        TransactionRunner::class,
                        ImageStorage::class,
                        NetworkMonitor::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                        GenreRepository::class,
                        TagRepository::class,
                        MoodRepository::class,
                        RpcChannel::class,
                        ImageRepository::class,
                        ImageStagingRepository::class,
                        SyncDomainHandler::class,
                    ),
            )
        }
    })
