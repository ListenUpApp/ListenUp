package com.calypsan.listenup.client.presentation.visibility

import app.cash.turbine.test
import com.calypsan.listenup.client.test.fake.FakeBookVisibilityRepository
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * [RestrictedBooksViewModel] — the one feed behind the lock on every book card. It passes the
 * repository's admin-gated set through as plain id strings, so every platform's card can test
 * the id it already holds; the member/admin gate itself is pinned in BookVisibilityRepositoryImplTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestrictedBooksViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("starts empty — a member's answer, and every device's until Room answers") {
            runTest(testDispatcher) {
                RestrictedBooksViewModel(FakeBookVisibilityRepository()).restrictedBookIds.value shouldBe emptySet()
            }
        }

        test("publishes the restricted ids as plain strings, and follows them live") {
            runTest(testDispatcher) {
                val visibility = FakeBookVisibilityRepository()
                val viewModel = RestrictedBooksViewModel(visibility)
                viewModel.restrictedBookIds.test {
                    awaitItem() shouldBe emptySet()

                    visibility.restrictedBookIds.value = setOf(BookId("b1"), BookId("b2"))
                    awaitItem() shouldBe setOf("b1", "b2")

                    visibility.restrictedBookIds.value = setOf(BookId("b2"))
                    awaitItem() shouldBe setOf("b2")

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("after close the value stops following the repository — the iOS deinit path") {
            runTest(testDispatcher) {
                val visibility = FakeBookVisibilityRepository()
                val viewModel = RestrictedBooksViewModel(visibility)
                backgroundScope.launch { viewModel.restrictedBookIds.collect {} }
                visibility.restrictedBookIds.value = setOf(BookId("b1"))
                advanceUntilIdle()
                viewModel.restrictedBookIds.value shouldBe setOf("b1")

                viewModel.close()
                visibility.restrictedBookIds.value = setOf(BookId("b2"))
                advanceUntilIdle()

                viewModel.restrictedBookIds.value shouldBe setOf("b1")
            }
        }
    })
