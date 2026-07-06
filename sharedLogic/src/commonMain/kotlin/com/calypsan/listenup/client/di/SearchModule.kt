package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.repository.SearchRepositoryImpl
import com.calypsan.listenup.client.data.sync.FtsPopulator
import com.calypsan.listenup.client.data.sync.FtsPopulatorContract
import com.calypsan.listenup.client.domain.repository.SearchRepository
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Search aggregate Koin wiring — repository and FTS populator for the
 * search domain.
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.data.local.db.SearchDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageStorage] — platform storage module
 *  - [com.calypsan.listenup.client.data.local.db.BookDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.ContributorDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.SeriesDao] — `persistenceModule`
 */
internal val searchModule: Module =
    module {
        // FtsPopulator for rebuilding FTS tables after sync
        single {
            FtsPopulator(
                bookDao = get(),
                contributorDao = get(),
                seriesDao = get(),
                searchDao = get(),
                transactionRunner = get(),
            )
        } bind FtsPopulatorContract::class

        // SearchRepository — local FTS5 search (no network round-trip; server runs the same algorithm)
        single<SearchRepository> {
            SearchRepositoryImpl(
                searchDao = get(),
                imageStorage = get(),
            )
        }
    }
