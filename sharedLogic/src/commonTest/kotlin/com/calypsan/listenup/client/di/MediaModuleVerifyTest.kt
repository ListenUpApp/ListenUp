package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.download.DownloadEnqueuer
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.CoroutineScope
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [mediaModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies
 * the media bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [ImageStorage] — owned by the platform storage module.
 *  - [CoroutineScope] named `appScope` — owned by `appCoreModule`.
 *  - [DownloadDao] — owned by `persistenceModule`.
 *  - [BookDao] — owned by `persistenceModule`.
 *  - [BookRepository] — owned by `bookModule`.
 *  - [DownloadEnqueuer] — owned by the platform download module.
 */
@OptIn(KoinExperimentalAPI::class)
class MediaModuleVerifyTest :
    FunSpec({

        test("mediaModule wires up against its declared external dependencies") {
            mediaModule.verify(
                extraTypes =
                    listOf(
                        ImageStorage::class,
                        CoroutineScope::class,
                        DownloadDao::class,
                        BookDao::class,
                        BookRepository::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                        DownloadEnqueuer::class,
                    ),
            )
        }
    })
