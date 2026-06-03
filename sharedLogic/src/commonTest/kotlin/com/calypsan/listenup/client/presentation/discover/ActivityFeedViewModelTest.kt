package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.api.result.AppResult
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for ActivityFeedViewModel.
 *
 * Tests cover:
 * - Initial `Loading` state before the pipeline subscribes
 * - Reactive `Ready` emissions from the repository flow
 * - `Error` emission when the upstream flow throws
 * - Initial fetch gate: skip when Room already has activities, run when empty
 * - `refresh()` delegating to `FetchActivitiesUseCase`
 *
 * Uses Mokkery for mocking `ActivityRepository` and `FetchActivitiesUseCase`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityFeedViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixtures ==========

    private class TestFixture {
        val activityRepository: ActivityRepository = mock()
        val fetchActivitiesUseCase: FetchActivitiesUseCase = mock()
        val activitiesFlow = MutableStateFlow<List<Activity>>(emptyList())

        fun build(): ActivityFeedViewModel =
            ActivityFeedViewModel(
                activityRepository = activityRepository,
                fetchActivitiesUseCase = fetchActivitiesUseCase,
            )
    }

    private fun createFixture(existingCount: Int = 0): TestFixture {
        val fixture = TestFixture()

        every { fixture.activityRepository.observeRecent(any()) } returns fixture.activitiesFlow
        everySuspend { fixture.activityRepository.count() } returns existingCount
        everySuspend { fixture.fetchActivitiesUseCase(any()) } returns AppResult.Success(0)

        return fixture
    }

    private fun TestScope.keepStateHot(viewModel: ActivityFeedViewModel) {
        backgroundScope.launch { viewModel.state.collect { } }
    }

    // ========== Test Data Factories ==========

    companion object {
        private fun createActivity(
            id: String = "activity-1",
            type: String = "started_book",
            userId: String = "user-1",
        ): Activity =
            Activity(
                id = id,
                type = type,
                userId = userId,
                createdAtMs = 0L,
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
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Initial State Tests ==========

    @Test
    fun `initial state is Loading before pipeline subscribes`() =
        runTest {
            // Given
            val fixture = createFixture()

            // When - viewModel created; `stateIn` initialValue is Loading
            // Do NOT start collecting here — the stateIn initialValue is what we assert.
            val viewModel = fixture.build()

            // Then - initial value is Loading (asserted before pipeline runs)
            assertIs<ActivityFeedUiState.Loading>(viewModel.state.value)
        }

    // ========== Reactive Observation Tests ==========

    @Test
    fun `Ready state emitted when repository flow emits activities`() =
        runTest {
            // Given
            val fixture = createFixture()
            val activity = createActivity(id = "activity-1")
            fixture.activitiesFlow.value = listOf(activity)

            // When
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<ActivityFeedUiState.Ready>(viewModel.state.value)
            assertEquals(1, ready.activities.size)
            assertEquals("activity-1", ready.activities.first().id)
            assertTrue(ready.hasData)
            assertFalse(ready.isEmpty)
        }

    @Test
    fun `Ready state has isEmpty true when activities list is empty`() =
        runTest {
            // Given - repository emits an empty list (default fixture state)
            val fixture = createFixture()

            // When
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<ActivityFeedUiState.Ready>(viewModel.state.value)
            assertTrue(ready.isEmpty)
            assertFalse(ready.hasData)
            assertTrue(ready.activities.isEmpty())
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `Error state emitted when repository flow throws`() =
        runTest {
            // Given - repository flow that throws
            val fixture = TestFixture()
            every { fixture.activityRepository.observeRecent(any()) } returns
                flow {
                    throw RuntimeException("boom")
                }
            everySuspend { fixture.activityRepository.count() } returns 0
            everySuspend { fixture.fetchActivitiesUseCase(any()) } returns AppResult.Success(0)

            // When
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val err = assertIs<ActivityFeedUiState.Error>(viewModel.state.value)
            assertEquals("Failed to load activity feed", err.message)
        }

    // ========== Initial Fetch Gate Tests ==========

    @Test
    fun `fetchInitialActivitiesIfNeeded triggers fetch when count is zero`() =
        runTest {
            // Given - Room is empty
            val fixture = createFixture(existingCount = 0)

            // When - init runs
            fixture.build()
            advanceUntilIdle()

            // Then - use case invoked with the initial fetch size
            verifySuspend { fixture.fetchActivitiesUseCase(INITIAL_FETCH_SIZE) }
        }

    @Test
    fun `fetchInitialActivitiesIfNeeded skips when count is positive`() =
        runTest {
            // Given - Room already has activities
            val fixture = createFixture(existingCount = 5)

            // When - init runs
            fixture.build()
            advanceUntilIdle()

            // Then - use case NOT invoked
            verifySuspend(VerifyMode.not) { fixture.fetchActivitiesUseCase(any()) }
        }

    // ========== Refresh Tests ==========

    @Test
    fun `refresh calls fetchActivitiesUseCase with INITIAL_FETCH_SIZE`() =
        runTest {
            // Given - Room already populated (prevent init fetch from confusing the verification)
            val fixture = createFixture(existingCount = 5)
            val viewModel = fixture.build()
            advanceUntilIdle()

            // When
            viewModel.refresh()
            advanceUntilIdle()

            // Then - refresh invokes the use case exactly once with the expected limit
            verifySuspend { fixture.fetchActivitiesUseCase(INITIAL_FETCH_SIZE) }
        }
}

/** Mirror of the private constant in the VM for verify clarity. */
private const val INITIAL_FETCH_SIZE = 50
