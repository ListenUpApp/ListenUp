package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.test.fake.FakeInboxRepository
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** The badge counts held books — never scan issues — and only ever for an admin. */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxBadgeViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeEach { Dispatchers.setMain(dispatcher) }
        afterEach { Dispatchers.resetMain() }

        class Fixture(
            isAdmin: Boolean,
            vararg heldIds: String,
        ) {
            val isAdminFlow = MutableStateFlow(isAdmin)
            val inbox = FakeInboxRepository().apply { hold(*heldIds) }
            val held get() = inbox.held
            val viewModel =
                InboxBadgeViewModel(
                    userRepository = mock<UserRepository> { every { observeIsAdmin() } returns isAdminFlow },
                    inboxRepository = inbox,
                )
        }

        test("an admin sees how many books are held") {
            runTest(dispatcher) {
                val f = Fixture(isAdmin = true, "b1", "b2", "b3")
                backgroundScope.launch { f.viewModel.heldCount.collect { } }
                advanceUntilIdle()

                f.viewModel.heldCount.value shouldBe 3
            }
        }

        test("the count follows holds and releases live, down to zero") {
            runTest(dispatcher) {
                val f = Fixture(isAdmin = true, "b1")
                backgroundScope.launch { f.viewModel.heldCount.collect { } }
                advanceUntilIdle()

                f.held.value = setOf(BookId("b1"), BookId("b2"))
                advanceUntilIdle()
                f.viewModel.heldCount.value shouldBe 2

                f.held.value = emptySet()
                advanceUntilIdle()
                f.viewModel.heldCount.value shouldBe 0
            }
        }

        test("anyone who is not an admin sees zero, whatever is held") {
            runTest(dispatcher) {
                val f = Fixture(isAdmin = false, "b1", "b2")
                backgroundScope.launch { f.viewModel.heldCount.collect { } }
                advanceUntilIdle()

                f.viewModel.heldCount.value shouldBe 0
            }
        }

        test("losing admin mid-session drops the count to zero") {
            runTest(dispatcher) {
                val f = Fixture(isAdmin = true, "b1", "b2")
                backgroundScope.launch { f.viewModel.heldCount.collect { } }
                advanceUntilIdle()
                f.viewModel.heldCount.value shouldBe 2

                f.isAdminFlow.value = false
                advanceUntilIdle()

                f.viewModel.heldCount.value shouldBe 0
            }
        }

        test("the preview is the newest three held books, newest first") {
            runTest(dispatcher) {
                val f = Fixture(isAdmin = true, "b1", "b2", "b3", "b4")
                backgroundScope.launch { f.viewModel.previewBookIds.collect { } }
                advanceUntilIdle()

                f.viewModel.previewBookIds.value shouldBe listOf("b4", "b3", "b2")
            }
        }

        test("the preview follows a release live") {
            runTest(dispatcher) {
                val f = Fixture(isAdmin = true, "b1", "b2")
                backgroundScope.launch { f.viewModel.previewBookIds.collect { } }
                advanceUntilIdle()

                f.held.value = setOf(BookId("b1"))
                advanceUntilIdle()

                f.viewModel.previewBookIds.value shouldBe listOf("b1")
            }
        }

        // iOS has no ViewModelStore: the observer closes the VM from its deinit (#1192).
        test("a closed view model stops following the inbox") {
            runTest(dispatcher) {
                val f = Fixture(isAdmin = true, "b1")
                backgroundScope.launch { f.viewModel.heldCount.collect { } }
                advanceUntilIdle()

                f.viewModel.close()
                f.held.value = setOf(BookId("b1"), BookId("b2"))
                advanceUntilIdle()

                f.viewModel.heldCount.value shouldBe 1
            }
        }

        test("anyone who is not an admin previews nothing") {
            runTest(dispatcher) {
                val f = Fixture(isAdmin = false, "b1", "b2")
                backgroundScope.launch { f.viewModel.previewBookIds.collect { } }
                advanceUntilIdle()

                f.viewModel.previewBookIds.value shouldBe emptyList()
            }
        }
    })
