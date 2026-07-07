package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.CollectionBookDao
import com.calypsan.listenup.client.data.local.db.CollectionDao
import com.calypsan.listenup.client.data.local.db.CollectionShareDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.LibraryRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [collectionModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies
 * the collection bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [CollectionDao] — owned by `persistenceModule`.
 *  - [CollectionBookDao] — owned by `persistenceModule`.
 *  - [CollectionShareDao] — owned by `persistenceModule`.
 *  - [LibraryRepository] — owned by `libraryModule` (CreateCollectionUseCase resolves the library id).
 */
@OptIn(KoinExperimentalAPI::class)
class CollectionModuleVerifyTest :
    FunSpec({

        test("collectionModule wires up against its declared external dependencies") {
            collectionModule.verify(
                extraTypes =
                    listOf(
                        CollectionDao::class,
                        CollectionBookDao::class,
                        CollectionShareDao::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                        LibraryRepository::class,
                    ),
            )
        }
    })
