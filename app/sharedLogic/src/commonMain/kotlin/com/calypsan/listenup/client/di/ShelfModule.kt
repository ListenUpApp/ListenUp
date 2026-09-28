package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.BookRatingRepositoryImpl
import com.calypsan.listenup.client.data.repository.ShelfRepositoryImpl
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.DeleteShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.LoadShelfDetailUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.RemoveBookFromShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.ReorderShelfBooksUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.UpdateShelfUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Shelf aggregate Koin wiring — RPC proxy, repository, and use cases for the
 * shelf domain.
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.local.db.ShelfDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.UserDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageRepository] — `mediaModule`
 *  - [com.calypsan.listenup.client.data.local.db.BookRatingDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.sync.OfflineEditor] — `clientSyncModule`
 *  - [com.calypsan.listenup.client.domain.repository.AuthSession] — `clientAuthModule`
 *
 * [BookRatingRepository] has no thematic tie to shelves — it is bound here (rather than a new
 * one-off DI file) following the same "reuse a nearby leaf module" convention `MoodRepository`
 * uses in `GenreTagModule.kt`.
 */
internal val shelfModule: Module =
    module {
        // ShelfService RPC channel — kotlinx.rpc dispatch for the shelf mutation and
        // discovery surface. Own-shelf reads come from Room (via ShelfDao); only mutations
        // and discovery need an RPC channel. Authed (self-healing) by default.
        rpcChannel<ShelfService>()

        // Listener ratings: reads from Room, writes offline-first through OfflineEditor on the
        // coalescing `book_ratings` outbox channel. bookRatingDao provided by persistenceModule.
        single<BookRatingRepository> {
            BookRatingRepositoryImpl(
                dao = get(),
                offlineEditor = get(),
                authSession = get(),
            )
        }

        // ShelfRepository for personal curation shelves (SOLID: interface in domain, impl in data)
        single<ShelfRepository> {
            ShelfRepositoryImpl(
                dao = get(),
                shelfBookDao = get(),
                userDao = get(),
                channel = rpcChannel(),
                offlineEditor = get(),
            )
        }

        // Shelf use cases
        factory {
            CreateShelfUseCase(
                shelfRepository = get(),
            )
        }
        factory {
            UpdateShelfUseCase(
                shelfRepository = get(),
            )
        }
        factory {
            DeleteShelfUseCase(
                shelfRepository = get(),
            )
        }
        factory {
            LoadShelfDetailUseCase(
                shelfRepository = get(),
                imageRepository = get(),
            )
        }
        factory {
            RemoveBookFromShelfUseCase(
                shelfRepository = get(),
            )
        }
        factory {
            AddBooksToShelfUseCase(
                shelfRepository = get(),
            )
        }
        factory {
            ReorderShelfBooksUseCase(
                shelfRepository = get(),
            )
        }
    }
