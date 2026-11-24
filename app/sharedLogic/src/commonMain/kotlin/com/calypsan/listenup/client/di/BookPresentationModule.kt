package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.bulkedit.BulkEditApplier
import com.calypsan.listenup.client.domain.model.signedInUserId
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsViewModel
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import com.calypsan.listenup.client.presentation.bookedit.BookEditViewModel
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditViewModel
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorViewModel
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverViewModel
import com.calypsan.listenup.client.presentation.match.BookMatchViewModel
import com.calypsan.listenup.client.presentation.match.MatchReceiptStore
import com.calypsan.listenup.client.presentation.match.MatchReceiptViewModel
import com.calypsan.listenup.client.presentation.match.PersonMatchViewModel
import com.calypsan.listenup.client.presentation.match.UndoMatch
import org.koin.dsl.module

/**
 * Book and content detail ViewModels.
 */
internal val bookPresentationModule =
    module {
        factory {
            BookDetailViewModel(
                bookRepository = get(),
                tagRepository = get(),
                playbackPositionRepository = get(),
                userRepository = get(),
                permissionsRepository = get(),
                shelfRepository = get(),
                collectionRepository = get(),
                addBooksToShelfUseCase = get(),
                createShelfUseCase = get(),
                errorBus = get(),
                bookAvailability = get(),
                serverReachability = get(),
                documentRepository = get(),
                inboxRepository = get(),
                bookVisibilityRepository = get(),
                bookEditRepository = get(),
                seriesRepository = get(),
                undoMatch = get(),
                userProfileRepository = get(),
                loadedBookRepository = get(),
                playhead = get(),
            )
        }
        factory { params ->
            BookReadersViewModel(
                repo = get(),
                bookId = params.get(),
                loadedBooks = get(),
                bookRepository = get(),
                activeSessions = get(),
                authSession = get(),
                serverReachability = get(),
            )
        }
        factory { params ->
            BookRatingsViewModel(
                bookId = params.get(),
                repository = get(),
                currentUserId = get<AuthSession>().authState.signedInUserId(),
                errorBus = get(),
                userRepository = get(),
            )
        }
        factory { params ->
            BookHardcoverViewModel(
                bookId = params.get(),
                repository = get(),
                errorBus = get(),
            )
        }
        factory {
            BookEditViewModel(
                loadBookForEditUseCase = get(),
                updateBookUseCase = get(),
                contributorRepository = get(),
                seriesRepository = get(),
                collectionRepository = get(),
                bookEditRepository = get(),
                userRepository = get(),
                inboxRepository = get(),
                imageStagingRepository = get(),
                errorBus = get(),
            )
        }
        // Hands a fresh match receipt from Match details to Book Detail or the contributor page; one per process.
        single { MatchReceiptStore() }
        factory<UndoMatch> {
            UndoMatch(matchingRepository = get())
        }
        // Match details for one book: Find, Review and Apply share one ViewModel per session.
        factory { params ->
            BookMatchViewModel(
                bookId = params.get(),
                matchingRepository = get(),
                bookRepository = get(),
                receiptStore = get(),
                errorBus = get(),
            )
        }
        // Match details for one person: As author | As narrator, Find, Review and Apply in one ViewModel.
        factory { params ->
            PersonMatchViewModel(
                contributorId = params.get(),
                matchingRepository = get(),
                contributorRepository = get(),
                receiptStore = get(),
                errorBus = get(),
            )
        }
        // The receipt after Apply, with Undo: on Book Detail for a book, the contributor page for a person.
        factory { params ->
            MatchReceiptViewModel(
                subjectId = params.get(),
                receiptStore = get(),
                undoMatch = get(),
                errorBus = get(),
            )
        }
        // Scoped to one book by parameter: the editor is always entered for a specific book and
        // never switches, so the id is a construction detail rather than something to re-set.
        factory { params ->
            ChapterEditorViewModel(
                bookId = params.get(),
                bookRepository = get(),
                bookEditRepository = get(),
                errorBus = get(),
                engine = get(),
                playerSession = get(),
            )
        }
        factory {
            BulkEditApplier(
                bookEditRepository = get(),
                tagRepository = get(),
                moodRepository = get(),
            )
        }
        // Scoped to one selection by parameter, for the same reason the chapter editor is scoped to
        // one book: the editor is entered for a specific set of books and never switches.
        factory { params ->
            BulkEditViewModel(
                bookIds = params.get(),
                bookRepository = get(),
                applier = get(),
                errorBus = get(),
                seriesRepository = get(),
                contributorRepository = get(),
                genreRepository = get(),
                tagRepository = get(),
                moodRepository = get(),
            )
        }
    }
