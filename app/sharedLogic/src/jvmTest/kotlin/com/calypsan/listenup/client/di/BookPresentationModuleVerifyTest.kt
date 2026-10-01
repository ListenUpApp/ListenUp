package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.HardcoverRepository
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
import kotlinx.coroutines.flow.Flow
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
 *  - [InboxRepository] — owned by `collectionModule`.
 *  - [BookReadersRepository] — owned by `socialModule`.
 *  - [BookRatingRepository] — owned by `shelfModule`.
 *  - [HardcoverRepository] — owned by `hardcoverClientModule` (the Book Detail Hardcover row).
 *  - [Flow] — Koin's verify resolves `BookRatingsViewModel`'s required `currentUserId:
 *    Flow<String?>` constructor param against the erased raw type (no default value to fall
 *    back on). The factory itself passes `get<AuthSession>().authState.signedInUserId()` at
 *    construction, not a Koin-resolved `Flow` — same shape as `ClientSyncModuleVerifyTest`'s
 *    `StateFlow` entry for `ConnectionHealthStore`.
 *  - [LoadBookForEditUseCase] — owned by `bookModule`.
 *  - [UpdateBookUseCase] — owned by `bookModule`.
 *  - [ContributorRepository] — owned by `contributorModule`.
 *  - [SeriesRepository] — owned by `seriesModule`.
 *  - [BookEditRepository] — owned by `bookModule`.
 *  - [ImageStagingRepository] — owned by `mediaModule`.
 *  - [MetadataRepository] — owned by `bookModule`.
 *  - [GenreRepository] — owned by `genreTagModule`.
 *  - [MoodRepository] — owned by `genreTagModule`.
 *  - [PlaybackManager] / [PlaybackController] — owned by the platform playback modules; the chapter
 *    editor's "Play from here" and its file boundaries read them.
 */
@OptIn(KoinExperimentalAPI::class)
class BookPresentationModuleVerifyTest :
    FunSpec({

        test("bookPresentationModule wires up against its declared external dependencies") {
            bookPresentationModule.verify(
                extraTypes =
                    listOf(
                        BookRepository::class,
                        PlaybackManager::class,
                        PlaybackController::class,
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
                        InboxRepository::class,
                        BookReadersRepository::class,
                        BookRatingRepository::class,
                        HardcoverRepository::class,
                        Flow::class,
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
