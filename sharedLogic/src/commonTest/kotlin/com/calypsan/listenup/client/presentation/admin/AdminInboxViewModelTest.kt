package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.AdminEvent
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.LibraryRepository
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [AdminInboxViewModel] (Collections-2a — 1b admin inbox REST).
 *
 * The inbox lists book ids via [InboxRepository.listInbox]; release builds a per-book
 * target-collection assignment map from local staging state and dispatches a single
 * [InboxRepository.releaseBooks]. The legacy stage/unstage round-trips are gone —
 * staging is local UI state collapsed into the release call.
 */
class AdminInboxViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeSpec { Dispatchers.setMain(dispatcher) }
        afterSpec { Dispatchers.resetMain() }

        class Fixture {
            val inboxRepo: InboxRepository = mock()
            val libraryRepo: LibraryRepository = mock()
            val eventStream: EventStreamRepository = mock()
            val adminEvents = MutableSharedFlow<AdminEvent>()

            fun build(): AdminInboxViewModel {
                every { libraryRepo.observeAll() } returns
                    MutableStateFlow(
                        listOf(
                            Library(
                                id = "lib1",
                                name = "Main",
                                metadataPrecedence = "",
                                accessMode = AccessMode.OPEN,
                                createdByUserId = null,
                                createdAt = 0L,
                                revision = 0L,
                            ),
                        ),
                    )
                every { eventStream.adminEvents } returns adminEvents
                return AdminInboxViewModel(inboxRepo, libraryRepo, eventStream, ErrorBus())
            }
        }

        test("Ready emitted with inbox book ids") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.inboxRepo.listInbox("lib1") } returns AppResult.Success(listOf("b1", "b2"))
                val vm = f.build()
                advanceUntilIdle()
                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.bookIds shouldBe listOf("b1", "b2")
            }
        }

        test("listInbox failure yields Error on initial load") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.inboxRepo.listInbox("lib1") } returns
                    AppResult.Failure(ValidationError(message = "forbidden"))
                val vm = f.build()
                advanceUntilIdle()
                val err = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Error>()
                err.message shouldBe "forbidden"
            }
        }

        test("releaseSelected builds the assignment map and dispatches one release") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.inboxRepo.listInbox("lib1") } returns AppResult.Success(listOf("b1", "b2"))
                everySuspend { f.inboxRepo.releaseBooks(any(), any()) } returns AppResult.Success(Unit)
                val vm = f.build()
                advanceUntilIdle()

                vm.stageCollection("b1", "col1")
                vm.toggleBookSelection("b1")
                vm.toggleBookSelection("b2") // selected, no staged collection => public
                vm.releaseSelected()
                advanceUntilIdle()

                verifySuspend {
                    f.inboxRepo.releaseBooks("lib1", mapOf("b1" to listOf("col1"), "b2" to emptyList()))
                }
                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.bookIds shouldBe emptyList()
                ready.lastReleasedCount shouldBe 2
            }
        }

        test("releaseSelected failure surfaces a transient error and keeps books") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.inboxRepo.listInbox("lib1") } returns AppResult.Success(listOf("b1"))
                everySuspend { f.inboxRepo.releaseBooks(any(), any()) } returns
                    AppResult.Failure(ValidationError(message = "release failed"))
                val vm = f.build()
                advanceUntilIdle()

                vm.toggleBookSelection("b1")
                vm.releaseSelected()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.error shouldBe "release failed"
                ready.bookIds shouldBe listOf("b1")
                ready.isReleasing shouldBe false
            }
        }

        test("InboxBookReleased SSE event removes the book locally") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.inboxRepo.listInbox("lib1") } returns AppResult.Success(listOf("b1", "b2"))
                val vm = f.build()
                advanceUntilIdle()

                f.adminEvents.emit(AdminEvent.InboxBookReleased(bookId = "b1"))
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminInboxUiState.Ready>()
                ready.bookIds shouldBe listOf("b2")
            }
        }
    })
