package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.book.LoadBookForEditUseCase
import com.calypsan.listenup.client.domain.usecase.book.UpdateBookUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [bookPresentationModule]. Per the architecture rubric every leaf Koin module
 * is covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies
 * that [bookPresentationModule] pulls in but other modules own:
 *
 *  - [BookRepository] — owned by `bookModule`.
 *  - [TagRepository] — owned by `genreTagModule`.
 *  - [PlaybackPositionRepository] — owned by `listeningModule`.
 *  - [UserRepository] — owned by `socialModule`.
 *  - [ShelfRepository] — owned by `shelfModule`.
 *  - [CollectionRepository] — owned by `collectionModule`.
 *  - [AddBooksToShelfUseCase] — owned by `shelfModule`.
 *  - [CreateShelfUseCase] — owned by `shelfModule`.
 *  - [ErrorBus] — owned by `appCoreModule`.
 *  - [BookAvailability] — owned by `clientSyncModule`.
 *  - [ServerReachability] — owned by `clientSyncModule`.
 *  - [DocumentRepository] — owned by `mediaModule`.
 *  - [BookReadersRepository] — owned by `socialModule`.
 *  - [LoadBookForEditUseCase] — owned by `bookModule`.
 *  - [UpdateBookUseCase] — owned by `bookModule`.
 *  - [ContributorRepository] — owned by `contributorModule`.
 *  - [SeriesRepository] — owned by `seriesModule`.
 *  - [BookEditRepository] — owned by `bookModule`.
 *  - [ImageStagingRepository] — owned by `mediaModule`.
 *  - [MetadataRepository] — owned by `bookModule`.
 *  - [GenreRepository] — owned by `genreTagModule`.
 *  - [MoodRepository] — owned by `genreTagModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class BookPresentationModuleVerifyTest :
    FunSpec({

        test("bookPresentationModule wires up against its declared external dependencies") {
            bookPresentationModule.verify(
                extraTypes =
                    listOf(
                        BookRepository::class,
                        TagRepository::class,
                        PlaybackPositionRepository::class,
                        UserRepository::class,
                        ShelfRepository::class,
                        CollectionRepository::class,
                        AddBooksToShelfUseCase::class,
                        CreateShelfUseCase::class,
                        ErrorBus::class,
                        BookAvailability::class,
                        ServerReachability::class,
                        DocumentRepository::class,
                        BookReadersRepository::class,
                        LoadBookForEditUseCase::class,
                        UpdateBookUseCase::class,
                        ContributorRepository::class,
                        SeriesRepository::class,
                        BookEditRepository::class,
                        ImageStagingRepository::class,
                        MetadataRepository::class,
                        GenreRepository::class,
                        MoodRepository::class,
                    ),
            )
        }
    })
