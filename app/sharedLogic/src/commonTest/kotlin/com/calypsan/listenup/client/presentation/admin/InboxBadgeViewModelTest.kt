package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
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
            val held = MutableStateFlow(heldIds.map { BookId(it) }.toSet())
            val viewModel =
                InboxBadgeViewModel(
                    userRepository = mock<UserRepository> { every { observeIsAdmin() } returns isAdminFlow },
                    inboxRepository = mock<InboxRepository> { every { observeHeldBookIds() } returns held },
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
    })
