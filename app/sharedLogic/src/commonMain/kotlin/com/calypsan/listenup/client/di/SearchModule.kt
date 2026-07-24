package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.SearchRepositoryImpl
import com.calypsan.listenup.client.data.sync.FtsPopulator
import com.calypsan.listenup.client.data.sync.FtsPopulatorContract
import com.calypsan.listenup.client.domain.repository.SearchRepository
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Search aggregate Koin wiring — repository, FTS populator, and the unified
 * [SearchService] RPC channel for the search domain.
 *
 * The [SearchService] channel is the single owner of the unified server search: the
 * contributor and series autocomplete repos resolve it as an external dependency for
 * their never-stranded server search (mirroring how `bookModule` resolves the
 * `CollectionService` channel `collectionModule` owns).
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.local.db.SearchDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageStorage] — platform storage module
 *  - [com.calypsan.listenup.client.data.local.db.BookDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.ContributorDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.SeriesDao] — `persistenceModule`
 */
internal val searchModule: Module =
    module {
        // SearchService RPC channel — kotlinx.rpc dispatch for the unified full-text search across
        // books/contributors/series/tags. Authed (self-healing) by default; joins the
        // RpcCacheInvalidator sweep. Contributor/series autocomplete repos resolve this channel.
        rpcChannel<SearchService>()

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
