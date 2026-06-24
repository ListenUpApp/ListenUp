package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.ActivityRefreshSignal
import com.calypsan.listenup.client.domain.model.Activity
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.domain.usecase.activity.FetchActivitiesUseCase
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for ActivityFeedViewModel.
 *
 * Tests cover:
 * - Initial `Loading` state before the pipeline subscribes
 * - Reactive `Ready` emissions from the repository flow
 * - `Error` emission when the upstream flow throws
 * - `refresh()` delegating to `FetchActivitiesUseCase`
 *
 * Uses Mokkery for mocking `ActivityRepository` and `FetchActivitiesUseCase`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityFeedViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixtures ==========

        class TestFixture {
            val activityRepository: ActivityRepository = mock()
            val fetchActivitiesUseCase: FetchActivitiesUseCase = mock()
            val refreshSignal = ActivityRefreshSignal()
            val activitiesFlow = MutableStateFlow<List<Activity>>(emptyList())

            fun build(): ActivityFeedViewModel =
                ActivityFeedViewModel(
                    activityRepository = activityRepository,
                    fetchActivitiesUseCase = fetchActivitiesUseCase,
                    refreshSignal = refreshSignal,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()

            every { fixture.activityRepository.observeRecent(any()) } returns fixture.activitiesFlow
            everySuspend { fixture.fetchActivitiesUseCase(any()) } returns AppResult.Success(0)

            return fixture
        }

        fun TestScope.keepStateHot(viewModel: ActivityFeedViewModel) {
            backgroundScope.launch { viewModel.state.collect { } }
        }

        // ========== Test Data Factories ==========

        fun createActivity(
            id: String = "activity-1",
            type: String = "started_book",
            userId: String = "user-1",
        ): Activity =
            Activity(
                id = id,
                type = type,
                userId = userId,
                occurredAtMs = 0L,
                user =
                    Activity.ActivityUser(
                        displayName = "Reader",
                        avatarColor = "blue",
                        avatarType = "initials",
                        avatarValue = null,
                    ),
                book = null,
                isReread = false,
                durationMs = 0L,
                milestoneValue = 0,
                milestoneUnit = null,
                shelfId = null,
                shelfName = null,
            )

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== Initial State Tests ==========

        test("initial state is Loading before pipeline subscribes") {
            runTest {
                // Given
                val fixture = createFixture()

                // When - viewModel created; `stateIn` initialValue is Loading
                // Do NOT start collecting here — the stateIn initialValue is what we assert.
                val viewModel = fixture.build()

                // Then - initial value is Loading (asserted before pipeline runs)
                viewModel.state.value.shouldBeInstanceOf<ActivityFeedUiState.Loading>()
            }
        }

        // ========== Reactive Observation Tests ==========

        test("Ready state emitted when repository flow emits activities") {
            runTest {
                // Given
                val fixture = createFixture()
                val activity = createActivity(id = "activity-1")
                fixture.activitiesFlow.value = listOf(activity)

                // When
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                val ready = viewModel.state.value.shouldBeInstanceOf<ActivityFeedUiState.Ready>()
                ready.activities.size shouldBe 1
                ready.activities.first().id shouldBe "activity-1"
                ready.hasData shouldBe true
                ready.isEmpty shouldBe false
            }
        }

        test("Ready state has isEmpty true when activities list is empty") {
            runTest {
                // Given - repository emits an empty list (default fixture state)
                val fixture = createFixture()

                // When
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                val ready = viewModel.state.value.shouldBeInstanceOf<ActivityFeedUiState.Ready>()
                ready.isEmpty shouldBe true
                ready.hasData shouldBe false
                ready.activities.isEmpty() shouldBe true
            }
        }

        // ========== Error Handling Tests ==========

        test("Error state emitted when repository flow throws") {
            runTest {
                // Given - repository flow that throws
                val fixture = TestFixture()
                every { fixture.activityRepository.observeRecent(any()) } returns
                    flow {
                        throw RuntimeException("boom")
                    }
                everySuspend { fixture.fetchActivitiesUseCase(any()) } returns AppResult.Success(0)

                // When
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                val err = viewModel.state.value.shouldBeInstanceOf<ActivityFeedUiState.Error>()
                err.message shouldBe "Failed to load activity feed"
            }
        }

        // ========== Refresh Tests ==========

        test("refresh calls fetchActivitiesUseCase with INITIAL_FETCH_SIZE") {
            runTest {
                // Given - a built ViewModel
                val fixture = createFixture()
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.refresh()
                advanceUntilIdle()

                // Then - refresh invokes the use case exactly once with the expected limit
                verifySuspend { fixture.fetchActivitiesUseCase(INITIAL_FETCH_SIZE) }
            }
        }

        // ========== Refresh-Signal Tests ==========

        test("refresh signal ping re-fetches the feed head") {
            runTest {
                // Given - a built ViewModel subscribed to the refresh signal
                val fixture = createFixture()
                fixture.build()
                advanceUntilIdle()

                // When - the server signals the feed may have changed
                fixture.refreshSignal.ping()
                advanceUntilIdle()

                // Then - the feed head is re-fetched into Room (Room observation repaints the UI)
                verifySuspend(VerifyMode.exactly(1)) { fixture.fetchActivitiesUseCase(INITIAL_FETCH_SIZE) }
            }
        }
    })

/** Mirror of the private constant in the VM for verify clarity. */
private const val INITIAL_FETCH_SIZE = 50
