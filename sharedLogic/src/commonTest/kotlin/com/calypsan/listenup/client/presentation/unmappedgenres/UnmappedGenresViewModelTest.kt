@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.presentation.unmappedgenres

import com.calypsan.listenup.api.dto.UnmappedStringSummary
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.presentation.error.userMessageFor
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentiallyReturns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Characterization tests for [UnmappedGenresViewModel].
 *
 * Covers:
 * - init calls refreshUnmapped and transitions to Ready with the fetched queue
 * - mapToGenre dispatches the mapping then re-fetches the queue on success
 * - listUnmappedStrings failure surfaces a transient error on Ready
 * - clearError resets the transient error to null
 *
 * Uses Mokkery for GenreRepository, MutableStateFlow upstream (not flowOf) per
 * test_stateflow_use_mutablestateflow memory note.
 */
class UnmappedGenresViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        beforeEach { Dispatchers.setMain(testDispatcher) }
        afterEach { Dispatchers.resetMain() }

        fun summary(
            raw: String,
            count: Int = 1,
        ): UnmappedStringSummary = UnmappedStringSummary(rawString = raw, bookCount = count, firstSeenAt = 0L)

        fun genre(
            id: String = "g1",
            path: String = "/fiction",
        ): Genre = Genre(id = id, name = "Fiction", slug = "fiction", path = path)

        test("init refreshes unmapped queue into Ready") {
            runTest {
                val repo: GenreRepository = mock()
                every { repo.observeAll() } returns MutableStateFlow(listOf(genre()))
                everySuspend { repo.listUnmappedStrings() } returns
                    AppResult.Success(listOf(summary("scifi"), summary("horror")))
                val vm = UnmappedGenresViewModel(genreRepository = repo, errorBus = ErrorBus())
                backgroundScope.launch { vm.state.collect { } }
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<UnmappedGenresUiState.Ready>()
                ready.unmapped.map { it.rawString } shouldBe listOf("scifi", "horror")
                ready.isLoadingUnmapped shouldBe false
            }
        }

        test("mapToGenre dispatches mapping then re-fetches the queue") {
            runTest {
                val repo: GenreRepository = mock()
                every { repo.observeAll() } returns MutableStateFlow(listOf(genre()))
                everySuspend { repo.listUnmappedStrings() } sequentiallyReturns
                    listOf(
                        AppResult.Success(listOf(summary("scifi"), summary("horror"))),
                        AppResult.Success(listOf(summary("horror"))),
                    )
                everySuspend { repo.mapUnmappedToGenre(any(), any()) } returns AppResult.Success(Unit)
                val vm = UnmappedGenresViewModel(genreRepository = repo, errorBus = ErrorBus())
                backgroundScope.launch { vm.state.collect { } }
                advanceUntilIdle()

                vm.mapToGenre(rawString = "scifi", genreId = GenreId("g1"))
                advanceUntilIdle()

                verifySuspend { repo.mapUnmappedToGenre("scifi", GenreId("g1")) }
                val ready = vm.state.value.shouldBeInstanceOf<UnmappedGenresUiState.Ready>()
                ready.unmapped.map { it.rawString } shouldBe listOf("horror")
                ready.isSaving shouldBe false
            }
        }

        test("listUnmappedStrings failure surfaces transient error") {
            runTest {
                val repo: GenreRepository = mock()
                every { repo.observeAll() } returns MutableStateFlow(listOf(genre()))
                val error = TransportError.Server5xx(statusCode = 500, debugInfo = "boom")
                everySuspend { repo.listUnmappedStrings() } returns AppResult.Failure(error)
                val vm = UnmappedGenresViewModel(genreRepository = repo, errorBus = ErrorBus())
                backgroundScope.launch { vm.state.collect { } }
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<UnmappedGenresUiState.Ready>()
                ready.error shouldBe userMessageFor(error)
                ready.isLoadingUnmapped shouldBe false
            }
        }

        test("clearError resets the transient error to null") {
            runTest {
                val repo: GenreRepository = mock()
                every { repo.observeAll() } returns MutableStateFlow(listOf(genre()))
                everySuspend { repo.listUnmappedStrings() } returns
                    AppResult.Failure(TransportError.Server5xx(statusCode = 500, debugInfo = "x"))
                val vm = UnmappedGenresViewModel(genreRepository = repo, errorBus = ErrorBus())
                backgroundScope.launch { vm.state.collect { } }
                advanceUntilIdle()

                vm.clearError()
                advanceUntilIdle()

                vm.state.value
                    .shouldBeInstanceOf<UnmappedGenresUiState.Ready>()
                    .error shouldBe null
            }
        }
    })
