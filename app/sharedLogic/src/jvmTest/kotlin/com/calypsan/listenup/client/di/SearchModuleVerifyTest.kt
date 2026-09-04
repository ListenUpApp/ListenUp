package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [searchModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies
 * the search bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule` (pulled in by the SearchService channel).
 *  - [ServerConfig] — owned by `settingsModule` (pulled in by the SearchService channel).
 *  - [SearchDao] — owned by `persistenceModule`.
 *  - [ImageStorage] — owned by the platform storage module.
 *  - [BookDao] — owned by `persistenceModule`.
 *  - [ContributorDao] — owned by `persistenceModule`.
 *  - [SeriesDao] — owned by `persistenceModule`.
 *  - [TransactionRunner] — owned by `persistenceModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class SearchModuleVerifyTest :
    FunSpec({

        test("searchModule wires up against its declared external dependencies") {
            searchModule.verify(
                extraTypes =
                    listOf(
                        SearchDao::class,
                        ImageStorage::class,
                        BookDao::class,
                        ContributorDao::class,
                        SeriesDao::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                        TransactionRunner::class,
                    ),
            )
        }
    })
