package com.calypsan.listenup.client.presentation.notifications

import com.calypsan.listenup.client.domain.repository.NotificationRepository
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

/** The bell's unread count follows the notifications table until the view model is closed. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationBellViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeEach { Dispatchers.setMain(dispatcher) }
        afterEach { Dispatchers.resetMain() }

        test("the count follows the unread notifications live") {
            runTest(dispatcher) {
                val unread = MutableStateFlow(2)
                val viewModel =
                    NotificationBellViewModel(mock<NotificationRepository> { every { observeUnreadCount() } returns unread })
                backgroundScope.launch { viewModel.unreadCount.collect { } }
                advanceUntilIdle()
                viewModel.unreadCount.value shouldBe 2

                unread.value = 5
                advanceUntilIdle()
                viewModel.unreadCount.value shouldBe 5
            }
        }

        // iOS has no ViewModelStore: the observer closes the VM from its deinit (#1192).
        test("a closed view model stops following the count") {
            runTest(dispatcher) {
                val unread = MutableStateFlow(2)
                val viewModel =
                    NotificationBellViewModel(mock<NotificationRepository> { every { observeUnreadCount() } returns unread })
                backgroundScope.launch { viewModel.unreadCount.collect { } }
                advanceUntilIdle()

                viewModel.close()
                unread.value = 5
                advanceUntilIdle()

                viewModel.unreadCount.value shouldBe 2
            }
        }
    })
