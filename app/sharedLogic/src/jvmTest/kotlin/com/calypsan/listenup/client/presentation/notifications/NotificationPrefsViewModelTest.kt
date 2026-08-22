package com.calypsan.listenup.client.presentation.notifications

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [NotificationPrefsViewModel] over the seam-level [FakeNotificationRepository]. Pins the
 * load → Data/Error mapping, the optimistic toggle with repository delegation, and the
 * revert-plus-ErrorBus path when the server refuses an update.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPrefsViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun pref(
            type: String,
            inApp: Boolean = true,
            push: Boolean = true,
            pushEligible: Boolean = true,
        ) = NotificationPreferenceDto(
            type = type,
            preference = NotificationPreference(inApp = inApp, push = push),
            pushEligible = pushEligible,
        )

        test("load maps to Data with one row per repository DTO, pushEligible mirrored") {
            runTest {
                val prefs = listOf(pref("campfire_invite"), pref("registration_decision", pushEligible = false))
                val repo = FakeNotificationRepository()
                repo.preferencesResult = AppResult.Success(prefs)
                val viewModel = NotificationPrefsViewModel(repo, ErrorBus())

                viewModel.uiState.test {
                    awaitItem() shouldBe NotificationPrefsUiState.Loading
                    val data = awaitItem() as NotificationPrefsUiState.Data
                    data.prefs shouldBe prefs
                    data.prefs[1].pushEligible shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("setPreference applies the toggle optimistically and delegates to the repository") {
            runTest {
                val repo = FakeNotificationRepository()
                repo.preferencesResult = AppResult.Success(listOf(pref("campfire_invite"), pref("registration_approval")))
                val viewModel = NotificationPrefsViewModel(repo, ErrorBus())
                val muted = NotificationPreference(inApp = false, push = true)

                viewModel.uiState.test {
                    awaitItem() shouldBe NotificationPrefsUiState.Loading
                    awaitItem()
                    viewModel.setPreference("campfire_invite", muted)
                    val optimistic = awaitItem() as NotificationPrefsUiState.Data
                    optimistic.prefs.first { it.type == "campfire_invite" }.preference shouldBe muted
                    optimistic.prefs.first { it.type == "registration_approval" }.preference shouldBe
                        NotificationPreference(inApp = true, push = true)
                    advanceUntilIdle()
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }

                repo.updatePreferenceCalls shouldContainExactly listOf("campfire_invite" to muted)
            }
        }

        test("a refused toggle reverts to the pre-toggle value and surfaces the error") {
            runTest {
                val error: AppError = TransportError.Timeout()
                val repo = FakeNotificationRepository()
                repo.preferencesResult = AppResult.Success(listOf(pref("campfire_invite")))
                repo.updatePreferenceResult = AppResult.Failure(error)
                val errorBus = ErrorBus()
                val viewModel = NotificationPrefsViewModel(repo, errorBus)

                errorBus.errors.test {
                    viewModel.uiState.test {
                        awaitItem() shouldBe NotificationPrefsUiState.Loading
                        val before = awaitItem()
                        viewModel.setPreference("campfire_invite", NotificationPreference(inApp = false, push = false))
                        awaitItem() // optimistic
                        awaitItem() shouldBe before
                        cancelAndIgnoreRemainingEvents()
                    }
                    awaitItem() shouldBe error
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a failed load lands in Error carrying the typed AppError") {
            runTest {
                val error: AppError = TransportError.Timeout()
                val repo = FakeNotificationRepository()
                repo.preferencesResult = AppResult.Failure(error)
                val viewModel = NotificationPrefsViewModel(repo, ErrorBus())

                viewModel.uiState.test {
                    awaitItem() shouldBe NotificationPrefsUiState.Loading
                    awaitItem() shouldBe NotificationPrefsUiState.Error(error)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
