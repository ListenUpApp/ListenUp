package com.calypsan.listenup.client.presentation.notifications

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AppNotification
import com.calypsan.listenup.client.domain.repository.NotificationRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [NotificationsViewModel] and [NotificationBellViewModel] over a seam-level fake
 * [NotificationRepository]. Pins the sealed [NotificationsUiState] mapping (Loading → Empty/Data),
 * markRead delegation with ErrorBus surfacing on failure, and the bell's unread-count pass-through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun notification(
            id: String,
            createdAt: Long = 1_000L,
            readAt: Long? = null,
        ) = AppNotification(
            id = id,
            type = "registration_approval",
            event = NotificationEvent.RegistrationApproval(userId = "u-1"),
            createdAt = createdAt,
            readAt = readAt,
        )

        test("an empty inbox maps Loading then Empty") {
            runTest {
                val repo = FakeNotificationRepository()
                val viewModel = NotificationsViewModel(repo, ErrorBus())

                viewModel.uiState.test {
                    awaitItem() shouldBe NotificationsUiState.Loading
                    awaitItem() shouldBe NotificationsUiState.Empty
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("inbox rows map to Data in the repository's newest-first order with unread flags intact") {
            runTest {
                val newest = notification(id = "n-2", createdAt = 2_000L, readAt = null)
                val older = notification(id = "n-1", createdAt = 1_000L, readAt = 1_500L)
                val repo = FakeNotificationRepository()
                repo.notifications.value = listOf(newest, older)
                val viewModel = NotificationsViewModel(repo, ErrorBus())

                viewModel.uiState.test {
                    awaitItem() shouldBe NotificationsUiState.Loading
                    val data = awaitItem() as NotificationsUiState.Data
                    data.notifications shouldBe listOf(newest, older)
                    data.notifications[0].isUnread shouldBe true
                    data.notifications[1].isUnread shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("markRead delegates to the repository") {
            runTest {
                val repo = FakeNotificationRepository()
                val viewModel = NotificationsViewModel(repo, ErrorBus())

                viewModel.markRead("n-7")
                advanceUntilIdle()

                repo.markReadCalls shouldContainExactly listOf("n-7")
            }
        }

        test("a markRead failure reaches the ErrorBus and does not change uiState") {
            runTest {
                val error: AppError = TransportError.Timeout()
                val repo = FakeNotificationRepository()
                repo.notifications.value = listOf(notification(id = "n-1"))
                repo.markReadResult = AppResult.Failure(error)
                val errorBus = ErrorBus()
                val viewModel = NotificationsViewModel(repo, errorBus)

                errorBus.errors.test {
                    viewModel.uiState.test {
                        awaitItem() shouldBe NotificationsUiState.Loading
                        val data = awaitItem()
                        viewModel.markRead("n-1")
                        advanceUntilIdle()
                        expectNoEvents()
                        viewModel.uiState.value shouldBe data
                        cancelAndIgnoreRemainingEvents()
                    }
                    awaitItem() shouldBe error
                    cancelAndIgnoreRemainingEvents()
                }

                repo.markReadCalls shouldContainExactly listOf("n-1")
            }
        }

        test("the bell emits the repository's unread counts") {
            runTest {
                val repo = FakeNotificationRepository()
                val viewModel = NotificationBellViewModel(repo)

                viewModel.unreadCount.test {
                    awaitItem() shouldBe 0
                    repo.unreadCount.value = 3
                    awaitItem() shouldBe 3
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

/**
 * Seam-level in-memory fake: [MutableStateFlow]-backed observations, recorded calls,
 * scriptable results.
 */
internal class FakeNotificationRepository : NotificationRepository {
    val notifications = MutableStateFlow<List<AppNotification>>(emptyList())
    val unreadCount = MutableStateFlow(0)
    val markReadCalls = mutableListOf<String>()
    var markReadResult: AppResult<Unit> = AppResult.Success(Unit)
    var preferencesResult: AppResult<List<NotificationPreferenceDto>> = AppResult.Success(emptyList())
    val updatePreferenceCalls = mutableListOf<Pair<String, NotificationPreference>>()
    var updatePreferenceResult: AppResult<Unit> = AppResult.Success(Unit)

    override fun observeNotifications(): Flow<List<AppNotification>> = notifications

    override fun observeUnreadCount(): Flow<Int> = unreadCount

    override suspend fun markRead(notificationId: String): AppResult<Unit> {
        markReadCalls += notificationId
        return markReadResult
    }

    override suspend fun getPreferences(): AppResult<List<NotificationPreferenceDto>> = preferencesResult

    override suspend fun updatePreference(
        type: String,
        preference: NotificationPreference,
    ): AppResult<Unit> {
        updatePreferenceCalls += type to preference
        return updatePreferenceResult
    }
}
