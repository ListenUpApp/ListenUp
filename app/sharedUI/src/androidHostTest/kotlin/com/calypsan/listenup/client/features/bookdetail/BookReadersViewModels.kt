package com.calypsan.listenup.client.features.bookdetail

import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.ActiveSessionRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * A [BookReadersViewModel] over [readers] for Book Detail screen tests: nothing playing, no chapters, and nobody else
 * listening, so the Readers section shows exactly what [readers] says.
 */
internal fun bookReadersViewModel(
    readers: BookReadersRepository,
    bookId: String,
    loadedBooks: FakeLoadedBooks = FakeLoadedBooks(),
): BookReadersViewModel =
    BookReadersViewModel(
        repo = readers,
        bookId = bookId,
        loadedBooks = loadedBooks,
        bookRepository =
            mock<BookRepository>(MockMode.autoUnit) { every { observeChapters(any()) } returns flowOf(emptyList()) },
        activeSessions =
            mock<ActiveSessionRepository>(MockMode.autoUnit) {
                every { observeActiveSessions(any()) } returns flowOf(emptyList())
            },
        authSession =
            mock<AuthSession>(MockMode.autoUnit) {
                every { authState } returns MutableStateFlow<AuthState>(AuthState.Initializing)
            },
        serverReachability =
            mock<ServerReachability>(MockMode.autoUnit) {
                every { state } returns MutableStateFlow<Reachability>(Reachability.Unknown)
            },
    )
