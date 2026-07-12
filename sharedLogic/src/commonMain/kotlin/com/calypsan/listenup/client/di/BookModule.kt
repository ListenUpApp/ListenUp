package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.BookDetailJoinSources
import com.calypsan.listenup.client.data.repository.BookEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.BookMutationLocalApply
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
 *  - [com.calypsan.listenup.client.domain.repository.ImageRepository] — `mediaModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageStagingRepository] — `mediaModule`
 *  - the books [com.calypsan.listenup.client.data.sync.SyncDomainHandler] — `clientSyncModule`
 */
internal val bookModule: Module =
    module {
        // BookService RPC channel — kotlinx.rpc dispatch for BookService (on-demand fetch, search,
        // book/cover edits, and the Books outbox). Authed (self-healing) by default; joins the
        // RpcCacheInvalidator sweep.
        rpcChannel<BookService>()

        // MetadataLookupService RPC channel — kotlinx.rpc dispatch for external metadata lookups.
        rpcChannel<MetadataLookupService>()

        // MetadataRepository for metadata operations (SOLID: interface in domain, impl in data)
        single<MetadataRepository> {
            MetadataRepositoryImpl(channel = rpcChannel())
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
                channel = rpcChannel(),
                bookSyncDomainHandler = get<SyncDomainHandler<BookSyncPayload>>(named(SyncDomains.BOOKS.name)),
            )
        } binds arrayOf(BookIngestPort::class)

        // BookEditRepository — offline-first for every edit surface: each write does its optimistic
        // Room merge and enqueues a durable BookMutation on the `books` outbox channel (one
        // transaction). The outbox sender in `clientSyncModule` dispatches each variant to its RPC;
        // the SSE echo reconciles Room.
        single<BookEditRepository> {
            BookEditRepositoryImpl(
                offlineEditor = get(),
                localApply =
                    BookMutationLocalApply(
                        bookDao = get(),
                        bookContributorDao = get(),
                        contributorDao = get(),
                        bookSeriesDao = get(),
                        seriesDao = get(),
                        genreDao = get(),
                        chapterDao = get(),
                        collectionBookDao = get(),
                    ),
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
