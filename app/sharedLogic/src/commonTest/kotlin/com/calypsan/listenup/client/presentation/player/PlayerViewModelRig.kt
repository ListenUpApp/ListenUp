package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.usecase.series.ObserveNextInSeriesUseCase
import com.calypsan.listenup.client.playback.PlayerSessionRig
import com.calypsan.listenup.client.test.fake.FakeUserRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope

/** A [PlayerViewModel] over a [PlayerSessionRig]'s fakes, plus the Room reads only the projection makes. */
internal class PlayerViewModelRig(
    testScope: TestScope,
    storedBookId: String? = null,
    isAdmin: Boolean = false,
) {
    val s = PlayerSessionRig(testScope, storedBookId)
    val users = FakeUserRepository(initialIsAdmin = isAdmin)

    /** Every loaded book's documents. */
    val documentList = MutableStateFlow<List<BookDocument>>(emptyList())

    /** What [DocumentRepository.ensureLocal] answers. */
    var ensureLocalResult: AppResult<String> = AppResult.Success("/cache/doc.pdf")

    /** Every loaded book's listener ratings. */
    val ratingList = MutableStateFlow<List<ListenerRating>>(emptyList())

    /** Per book: while a gate is open (incomplete), the book's documents, and so its `PlayerBook`, have not loaded yet. */
    val documentGates = mutableMapOf<String, CompletableDeferred<Unit>>()

    val documents =
        mock<DocumentRepository> {
            every { observeDocuments(any()) } calls { (id: BookId) ->
                documentList.onStart { documentGates[id.value]?.await() }
            }
            everySuspend { ensureLocal(any(), any()) } calls { ensureLocalResult }
        }
    val contributors =
        mock<ContributorRepository> {
            every { observeRolesWithCountForContributor(any()) } returns flowOf(emptyList())
        }
    val ratings = mock<BookRatingRepository> { every { observeForBook(any()) } returns ratingList }
    val series = mock<SeriesRepository> { every { observeSeriesWithBooks(any()) } returns flowOf(null) }
    val errorBus = ErrorBus()
    val sheet = PlayerSheetState()

    /** A second VM over the same session and sheet, as a second screen's store would get. */
    fun newVm(): PlayerViewModel =
        PlayerViewModel(
            session = s.session,
            engine = s.engine,
            bookRepository = s.books,
            contributorRepository = contributors,
            documentRepository = documents,
            bookRatingRepository = ratings,
            observeNextInSeries = ObserveNextInSeriesUseCase(s.books, series, s.inbox),
            userRepository = users,
            authSession = s.auth,
            availability = s.availability,
            networkMonitor = s.network,
            serverReachability = s.reachability,
            playbackPreferences = s.preferences,
            sheetState = sheet,
            errorBus = errorBus,
        )

    val vm = newVm()

    fun close() = s.close()
}
