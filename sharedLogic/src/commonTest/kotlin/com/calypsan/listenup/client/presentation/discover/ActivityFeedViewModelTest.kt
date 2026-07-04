package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Activity
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [ActivityFeedViewModel].
 *
 * Activities are a Room-mirrored sync domain, so the ViewModel is a pure Room observer — there is
 * no per-screen fetch or refresh signal. Tests cover:
 * - Initial `Loading` state before the pipeline subscribes
 * - Reactive `Ready` emissions from the repository flow
 * - `Error` emission when the upstream flow throws
 *
 * Uses Mokkery for mocking [ActivityRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityFeedViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixtures ==========

        class TestFixture {
            val activityRepository: ActivityRepository = mock()
            val activitiesFlow = MutableStateFlow<List<Activity>>(emptyList())
            val syncRepository = RecordingSyncRepository()

            fun build(): ActivityFeedViewModel =
                ActivityFeedViewModel(
                    activityRepository = activityRepository,
                    syncRepository = syncRepository,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()

            every { fixture.activityRepository.observeRecent(any()) } returns fixture.activitiesFlow

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

                // When
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                val err = viewModel.state.value.shouldBeInstanceOf<ActivityFeedUiState.Error>()
                err.message shouldBe "Failed to load activity feed"
            }
        }

        // ========== Pull-to-refresh Tests ==========

        test("refresh routes to syncRepository.refresh (Never-Stranded manual reconcile)") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build()

                // When
                viewModel.refresh()
                advanceUntilIdle()

                // Then - the manual reconcile is forwarded to the sync engine
                fixture.syncRepository.refreshCalled.shouldBeTrue()
            }
        }
    })

/**
 * [SyncRepository] fake that records whether [refresh] was invoked — the only method the
 * ActivityFeed ViewModel touches. Everything else is a no-op stub.
 */
private class RecordingSyncRepository : SyncRepository {
    var refreshCalled: Boolean = false
        private set

    override val syncState: StateFlow<SyncState> = MutableStateFlow(SyncState.Idle)
    override val isServerScanning: StateFlow<Boolean> = MutableStateFlow(false)
    override val isBuildingInitialLibrary: StateFlow<Boolean> = MutableStateFlow(false)
    override val scanProgress: StateFlow<ScanProgressState?> = MutableStateFlow(null)

    override suspend fun sync(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun connectRealtime() = Unit

    override suspend fun disconnect() = Unit

    override suspend fun resetForNewLibrary(newLibraryId: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun refreshListeningHistory(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun forceFullResync(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun refresh(): AppResult<Unit> {
        refreshCalled = true
        return AppResult.Success(Unit)
    }

    override suspend fun hasLocalLibrary(): Boolean = true
}
