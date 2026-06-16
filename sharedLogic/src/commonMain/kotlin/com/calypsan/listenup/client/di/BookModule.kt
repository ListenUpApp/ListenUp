package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.client.data.remote.KtorBookRpcFactory
import com.calypsan.listenup.client.data.remote.KtorMetadataLookupRpcFactory
import com.calypsan.listenup.client.data.remote.MetadataLookupRpcFactory
import com.calypsan.listenup.client.data.repository.BookEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.BookRepositoryImpl
import com.calypsan.listenup.client.data.repository.MetadataRepositoryImpl
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.usecase.book.LoadBookForEditUseCase
import com.calypsan.listenup.client.domain.usecase.book.UpdateBookUseCase
import org.koin.core.module.Module
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
 *  - [com.calypsan.listenup.client.data.remote.CollectionRpcFactory] — `collectionModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageRepository] — `mediaModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageStagingRepository] — `mediaModule`
 *  - [com.calypsan.listenup.client.data.sync.handlers.BookSyncDomainHandler] — `clientSyncRenovationModule`
 */
val bookModule: Module =
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

        // BookRepository for UI data access
        single<BookRepository> {
            BookRepositoryImpl(
                bookDao = get(),
                chapterDao = get(),
                audioFileDao = get(),
                searchDao = get(),
                transactionRunner = get(),
                imageStorage = get(),
                genreRepository = get(),
                tagRepository = get(),
                moodRepository = get(),
                networkMonitor = get(),
                bookRpcFactory = get(),
                bookSyncDomainHandler = get(),
            )
        }

        // BookEditRepository — pure RPC dispatcher; SSE echoes write back into Room.
        single<BookEditRepository> {
            BookEditRepositoryImpl(
                bookRpcFactory = get(),
                collectionRpcFactory = get(),
            )
        }

        // Book use cases (using domain layer interfaces only)
        factory {
            LoadBookForEditUseCase(
                bookRepository = get(),
                genreRepository = get(),
                tagRepository = get(),
            )
        }
        factory {
            UpdateBookUseCase(
                bookEditRepository = get(),
                tagRepository = get(),
                imageRepository = get(),
                imageStagingRepository = get(),
            )
        }
    }
