package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.GenreRepositoryImpl
import com.calypsan.listenup.client.data.repository.MoodRepositoryImpl
import com.calypsan.listenup.client.data.repository.TagRepositoryImpl
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Genre and Tag aggregate Koin wiring — RPC proxies and repositories for the
 * genre and tag curation domains.
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.local.db.GenreDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.TagDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.BookTagDao] — `persistenceModule`
 */
internal val genreTagModule: Module =
    module {
        // GenreService RPC channel — kotlinx.rpc dispatch for the curator mutation
        // surface. Tree reads come from Room (via GenreDao); only mutations and the
        // unmapped-string queue need an RPC channel. Authed (self-healing) by default.
        rpcChannel<GenreService>()

        // GenreRepository — Room-backed reads, RPC-dispatched mutations.
        single<GenreRepository> {
            GenreRepositoryImpl(dao = get(), channel = rpcChannel())
        }

        // TagService RPC channel — kotlinx.rpc dispatch for the tag mutation surface
        // (observations come from Room via TagDao). Authed (self-healing) by default.
        rpcChannel<TagService>()

        // TagRepository — observations from Room, mutations via RPC (Tags phase).
        // DAOs are injected directly (tagDao, bookTagDao provided by persistenceModule).
        single<TagRepository> {
            TagRepositoryImpl(
                channel = rpcChannel(),
                tagDao = get(),
                bookTagDao = get(),
                offlineEditor = get(),
            )
        }

        // MoodService RPC channel — kotlinx.rpc dispatch for the mood mutation surface
        // (observations come from Room via MoodDao). Authed (self-healing) by default.
        rpcChannel<MoodService>()

        // MoodRepository — observations from Room, mutations via RPC (Mood phase).
        // moodDao and bookMoodDao provided by persistenceModule.
        single<MoodRepository> {
            MoodRepositoryImpl(
                channel = rpcChannel(),
                moodDao = get(),
                bookMoodDao = get(),
                offlineEditor = get(),
            )
        }
    }
