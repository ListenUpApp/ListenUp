package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [contributorModule]. Per the architecture rubric every leaf Koin module
 * is covered by a `module.verify()` test in commonTest. The whitelist enumerates
 * dependencies the contributor bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [ContributorDao] — owned by `persistenceModule`.
 *  - [BookDao] — owned by `persistenceModule`.
 *  - [SearchDao] — owned by `persistenceModule`.
 *  - the [RpcChannel] for `SearchService` — owned by `searchModule`.
 *  - [NetworkMonitor] — owned by the platform device module.
 *  - [ImageStorage] — owned by the platform storage module.
 *  - [SyncDomainHandler] (named `contributors`) — owned by `clientSyncModule`.
 *  - [MetadataRepository] — owned by `bookModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class ContributorModuleVerifyTest :
    FunSpec({

        test("contributorModule wires up against its declared external dependencies") {
            contributorModule.verify(
                extraTypes =
                    listOf(
                        ContributorDao::class,
                        BookDao::class,
                        SearchDao::class,
                        RpcChannel::class,
                        NetworkMonitor::class,
                        ImageStorage::class,
                        SyncDomainHandler::class,
                        MetadataRepository::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                    ),
            )
        }
    })
