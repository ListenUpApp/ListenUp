package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.client.data.remote.KtorBookRpcFactory
import com.calypsan.listenup.client.data.remote.KtorMetadataLookupRpcFactory
import com.calypsan.listenup.client.data.remote.MetadataLookupRpcFactory
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.BookDetailJoinSources
import com.calypsan.listenup.client.data.repository.BookEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.BookIngestPort
import com.calypsan.listenup.client.data.repository.BookRepositoryImpl
import com.calypsan.listenup.client.data.repository.MetadataRepositoryImpl
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.usecase.book.LoadBookForEditUseCase
import com.calypsan.listenup.client.domain.usecase.book.UpdateBookUseCase
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Book aggregate Koin wiring — RPC proxy, repositories, and use cases for the
 * book domain. Includes metadata lookup (R3: metadata travels with book).
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.local.db.BookDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.ChapterDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.AudioFileDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.SearchDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.TransactionRunner] — `persistenceModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageStorage] — platform storage module
 *  - [com.calypsan.listenup.client.domain.repository.NetworkMonitor] — platform device module
 *  - [com.calypsan.listenup.client.domain.repository.GenreRepository] — `genreTagModule`
 *  - [com.calypsan.listenup.client.domain.repository.TagRepository] — `genreTagModule`
 *  - the [com.calypsan.listenup.api.CollectionService] `RpcChannel` — `collectionModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageRepository] — `mediaModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageStagingRepository] — `mediaModule`
 *  - the books [com.calypsan.listenup.client.data.sync.SyncDomainHandler] — `clientSyncModule`
 */
internal val bookModule: Module =
    module {
        // BookRpcFactory - kotlinx.rpc proxy for BookService (on-demand fetch + search).
        // Mirrors AuthRpcFactory; fully functional end-to-end — the server registers
        // BookService on its bearer-gated /api/rpc/authed surface (landed in T28.5).
        single<BookRpcFactory> {
            KtorBookRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // MetadataLookupRpcFactory — kotlinx.rpc proxy for MetadataLookupService.
        single<MetadataLookupRpcFactory> {
            KtorMetadataLookupRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // MetadataRepository for metadata operations (SOLID: interface in domain, impl in data)
        single<MetadataRepository> {
            MetadataRepositoryImpl(rpcFactory = get())
        }

        // BookRepository for UI data access. Also binds BookIngestPort so
        // playback-layer ingest paths can write book aggregates without the
        // domain interface exposing Room entity types.
        single<BookRepository> {
            BookRepositoryImpl(
                bookDao = get(),
                chapterDao = get(),
                audioFileDao = get(),
                searchDao = get(),
                transactionRunner = get(),
                imageStorage = get(),
                joinSources =
                    BookDetailJoinSources(
                        genreRepository = get(),
                        tagRepository = get(),
                        moodRepository = get(),
                    ),
                networkMonitor = get(),
                bookRpcFactory = get(),
                bookSyncDomainHandler = get<SyncDomainHandler<BookSyncPayload>>(named(SyncDomains.BOOKS.name)),
            )
        } binds arrayOf(BookIngestPort::class)

        // BookEditRepository — offline-first updateBook (Room + outbox queue); the
        // remaining edits stay RPC-only with SSE echoes writing back into Room.
        single<BookEditRepository> {
            BookEditRepositoryImpl(
                bookRpcFactory = get(),
                collectionChannel = rpcChannel<CollectionService>(),
                bookDao = get(),
                offlineEditor = get(),
            )
        }

        // Book use cases (using domain layer interfaces only)
        factory {
            LoadBookForEditUseCase(
                bookRepository = get(),
                genreRepository = get(),
                tagRepository = get(),
                moodRepository = get(),
            )
        }
        factory {
            UpdateBookUseCase(
                bookEditRepository = get(),
                tagRepository = get(),
                moodRepository = get(),
                imageRepository = get(),
                imageStagingRepository = get(),
            )
        }
    }
