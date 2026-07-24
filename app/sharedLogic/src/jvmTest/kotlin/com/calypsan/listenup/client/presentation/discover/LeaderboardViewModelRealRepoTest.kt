@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.client.data.local.db.IdRevision
import com.calypsan.listenup.client.data.local.db.PublicProfileDao
import com.calypsan.listenup.client.data.local.db.PublicProfileEntity
import com.calypsan.listenup.client.data.repository.LeaderboardRepositoryImpl
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * End-to-end contract test: [LeaderboardViewModel] over the real [LeaderboardRepositoryImpl]
 * wired to a [FakePublicProfileDao] backed by a [MutableStateFlow].
 *
 * Distinct from the commonTest `LeaderboardViewModelTest` (which fakes the repository itself),
 * this test exercises the full stack from ViewModel → LeaderboardRepositoryImpl → DAO, proving
 * the rewired repo still satisfies the ViewModel contract.
 *
 * The fake DAO uses [MutableStateFlow] (not `flowOf`) to avoid the Turbine / WhileSubscribed
 * race that fires when a cold source completes before the downstream StateFlow collects.
 */
class LeaderboardViewModelRealRepoTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeEach { Dispatchers.setMain(testDispatcher) }
        afterEach { Dispatchers.resetMain() }

        // ── helpers ───────────────────────────────────────────────────────────

        fun entity(
            id: String,
            allTime: Long = 100L,
            last7: Long = 10L,
            last30: Long = 20L,
            last365: Long = 50L,
            booksFinished: Int = 1,
            currentStreak: Int = 2,
            longestStreak: Int = 5,
            booksW7: Int = 0,
            streakW7: Int = 0,
        ) = PublicProfileEntity(
            id = id,
            displayName = "User $id",
            avatarType = "auto",
            totalSecondsAllTime = allTime,
            totalSecondsLast7Days = last7,
            totalSecondsLast30Days = last30,
            totalSecondsLast365Days = last365,
            booksFinished = booksFinished,
            currentStreakDays = currentStreak,
            longestStreakDays = longestStreak,
            booksFinishedLast7Days = booksW7,
            longestStreakLast7Days = streakW7,
            revision = 1,
            deletedAt = null,
        )

        fun vmOver(rows: MutableStateFlow<List<PublicProfileEntity>>): LeaderboardViewModel {
            val dao = FakePublicProfileDao(rows)
            val repo = LeaderboardRepositoryImpl(dao)
            return LeaderboardViewModel(repo)
        }

        // ── 1. Loading → Data (AllTime/Time defaults) ─────────────────────────

        test("initial state is Loading before any collection") {
            runTest {
                val vm = vmOver(MutableStateFlow(emptyList()))
                vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Loading>()
            }
        }

        test("repo emission through real impl transitions to Data with AllTime/Time defaults") {
            runTest {
                val rows = MutableStateFlow(listOf(entity("alice", allTime = 500L)))
                val vm = vmOver(rows)

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                advanceUntilIdle()

                val data = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                data.period shouldBe LeaderboardPeriod.Week
                data.category shouldBe LeaderboardCategory.Time
                // Week uses last7; alice has last7=10 → single entry
                data.snapshot.time
                    .single()
                    .userId shouldBe "alice"
            }
        }

        // ── 2. selectCategory — no re-query, same snapshot instance ──────────

        test("selectCategory flips category without re-querying the DAO") {
            runTest {
                val rows =
                    MutableStateFlow(
                        listOf(
                            entity("alice", allTime = 500L, booksFinished = 3, longestStreak = 10),
                        ),
                    )
                val vm = vmOver(rows)

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                advanceUntilIdle()

                // Switch to AllTime so Books/Streak are populated
                vm.selectPeriod(LeaderboardPeriod.AllTime)
                advanceUntilIdle()

                val before = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                val snapshotBefore = before.snapshot

                vm.selectCategory(LeaderboardCategory.Books)
                advanceUntilIdle()

                val after = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                // Category changes
                after.category shouldBe LeaderboardCategory.Books
                // Snapshot is identical — no re-query
                after.snapshot shouldBe snapshotBefore
            }
        }

        // ── 3. selectPeriod — re-queries via real ranking logic ───────────────

        test("selectPeriod(AllTime) populates Books and Streak via real repo ranking") {
            runTest {
                val rows =
                    MutableStateFlow(
                        listOf(
                            entity("alice", allTime = 1000L, booksFinished = 5, longestStreak = 3),
                            entity("bob", allTime = 800L, booksFinished = 9, longestStreak = 1),
                        ),
                    )
                val vm = vmOver(rows)

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                advanceUntilIdle()

                vm.selectPeriod(LeaderboardPeriod.AllTime)
                advanceUntilIdle()

                val data = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                data.period shouldBe LeaderboardPeriod.AllTime
                // Time: alice wins on allTime
                data.snapshot.time
                    .first()
                    .userId shouldBe "alice"
                // Books: bob has 9 finished
                data.snapshot.books
                    .first()
                    .userId shouldBe "bob"
                // Streak: alice has longest=3
                data.snapshot.streak
                    .first()
                    .userId shouldBe "alice"
            }
        }

        // ── 4. Bounded period ranks Books/Streak by the windowed columns ──────

        test("selectPeriod(Week) populates Books and Streak from the 7-day windowed columns") {
            runTest {
                val rows =
                    MutableStateFlow(
                        listOf(entity("alice", last7 = 60L, booksFinished = 5, longestStreak = 3, booksW7 = 2, streakW7 = 4)),
                    )
                val vm = vmOver(rows)

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                vm.selectPeriod(LeaderboardPeriod.Week)
                advanceUntilIdle()

                val data = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                // Bounded periods now carry the windowed values, not the all-time totals.
                data.snapshot.books.map { it.userId } shouldBe listOf("alice")
                data.snapshot.books
                    .first()
                    .booksFinished shouldBe 2
                data.snapshot.streak
                    .first()
                    .longestStreakDays shouldBe 4
            }
        }

        // ── 5. Empty roster → Empty state ────────────────────────────────────

        test("empty roster emits Empty state") {
            runTest {
                val rows = MutableStateFlow(emptyList<PublicProfileEntity>())
                val vm = vmOver(rows)

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                advanceUntilIdle()

                vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Empty>()
            }
        }

        // ── 6. Live roster update re-emits Data ───────────────────────────────

        test("roster update re-emits Data with new entries") {
            runTest {
                val rows = MutableStateFlow(listOf(entity("alice", last7 = 50L)))
                val vm = vmOver(rows)

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                advanceUntilIdle()

                // Add bob with higher last7
                rows.value = rows.value + entity("bob", last7 = 200L)
                advanceUntilIdle()

                val data = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                // bob should now lead the Time list (Week period, ranked by last7)
                data.snapshot.time
                    .first()
                    .userId shouldBe "bob"
                data.snapshot.time.size shouldBe 2
            }
        }
    })

private class FakePublicProfileDao(
    private val flow: MutableStateFlow<List<PublicProfileEntity>>,
) : PublicProfileDao {
    override fun observeAll(): Flow<List<PublicProfileEntity>> = flow

    override fun observeById(userId: String): Flow<PublicProfileEntity?> = error("unused in VM test")

    override suspend fun upsert(entity: PublicProfileEntity) = error("unused in VM test")

    override suspend fun findById(userId: String): PublicProfileEntity? = error("unused in VM test")

    override suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) = error("unused in VM test")

    override suspend fun digestRows(max: Long): List<IdRevision> = error("unused in VM test")

    override suspend fun revisionOf(id: String): Long? = error("unused in VM test")

    override suspend fun deleteAll() = error("unused in VM test")
}
