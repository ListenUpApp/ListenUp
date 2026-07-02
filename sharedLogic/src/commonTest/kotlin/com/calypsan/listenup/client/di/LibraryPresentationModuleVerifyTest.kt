package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.LibraryPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.SyncStatusRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.collection.AddBooksToCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [libraryPresentationModule]. Per the architecture rubric every leaf Koin module
 * is covered by a `module.verify()` test in commonTest. This proves the [LibraryViewModel],
 * [com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel], and the two search
 * ViewModels resolve at runtime. The whitelist enumerates dependencies the bindings pull in but
 * other modules own:
 *
 *  - [BookRepository] — owned by `bookModule`.
 *  - [SeriesRepository] — owned by `seriesModule`.
 *  - [ContributorRepository] — owned by `contributorModule`.
 *  - [PlaybackPositionRepository] — owned by `listeningModule`.
 *  - [SyncRepository] — owned by `clientSyncModule`.
 *  - [AuthSession] — owned by `clientAuthModule`.
 *  - [LibraryPreferences] — owned by `settingsModule`.
 *  - [SyncStatusRepository] — owned by `clientSyncModule`.
 *  - [UserRepository] — owned by `socialModule`.
 *  - [CollectionRepository] — owned by `collectionModule`.
 *  - [ShelfRepository] — owned by `shelfModule`.
 *  - [AddBooksToShelfUseCase] — owned by `shelfModule`.
 *  - [AddBooksToCollectionUseCase] — owned by `collectionModule`.
 *  - [CreateShelfUseCase] — owned by `shelfModule`.
 *  - [ErrorBus] — owned by `appCoreModule`.
 *  - [SearchRepository] — owned by `searchModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class LibraryPresentationModuleVerifyTest :
    FunSpec({

        test("libraryPresentationModule wires up against its declared external dependencies") {
            libraryPresentationModule.verify(
                extraTypes =
                    listOf(
                        BookRepository::class,
                        SeriesRepository::class,
                        ContributorRepository::class,
                        PlaybackPositionRepository::class,
                        SyncRepository::class,
                        AuthSession::class,
                        LibraryPreferences::class,
                        SyncStatusRepository::class,
                        UserRepository::class,
                        CollectionRepository::class,
                        ShelfRepository::class,
                        AddBooksToShelfUseCase::class,
                        AddBooksToCollectionUseCase::class,
                        CreateShelfUseCase::class,
                        ErrorBus::class,
                        SearchRepository::class,
                    ),
            )
        }
    })
