package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.client.data.local.db.IdRevision
import com.calypsan.listenup.client.data.local.db.PublicProfileDao
import com.calypsan.listenup.client.data.local.db.PublicProfileEntity
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

private fun entity(
    id: String,
    allTime: Long,
    last7: Long = 0,
    last30: Long = 0,
    last365: Long,
    finished: Int,
    currentStreak: Int = 0,
    longest: Int,
    booksW7: Int = 0,
    booksW30: Int = 0,
    booksW365: Int = 0,
    streakW7: Int = 0,
    streakW30: Int = 0,
    streakW365: Int = 0,
) = PublicProfileEntity(
    id = id,
    displayName = "User $id",
    avatarType = "auto",
    totalSecondsAllTime = allTime,
    totalSecondsLast7Days = last7,
    totalSecondsLast30Days = last30,
    totalSecondsLast365Days = last365,
    booksFinished = finished,
    currentStreakDays = currentStreak,
    longestStreakDays = longest,
    booksFinishedLast7Days = booksW7,
    booksFinishedLast30Days = booksW30,
    booksFinishedLast365Days = booksW365,
    longestStreakLast7Days = streakW7,
    longestStreakLast30Days = streakW30,
    longestStreakLast365Days = streakW365,
    revision = 1,
    deletedAt = null,
)

private class FakePublicProfileDao(
    private val flow: Flow<List<PublicProfileEntity>>,
) : PublicProfileDao {
    override fun observeAll(): Flow<List<PublicProfileEntity>> = flow

    override fun observeById(userId: String): Flow<PublicProfileEntity?> = error("unused")

    override suspend fun upsert(entity: PublicProfileEntity) = error("unused")

    override suspend fun findById(userId: String): PublicProfileEntity? = error("unused")

    override suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) = error("unused")

    override suspend fun digestRows(max: Long): List<IdRevision> = error("unused")

    override suspend fun revisionOf(id: String): Long? = error("unused")

    override suspend fun deleteAll() = error("unused")
}

class LeaderboardRepositoryImplTest :
    FunSpec({
        test("AllTime Time ranking is dense-ranked by totalSecondsAllTime; Books/Streak populated") {
            val rows =
                MutableStateFlow(
                    listOf(
                        entity("a", allTime = 300, last365 = 30, finished = 2, longest = 5),
                        entity("b", allTime = 300, last365 = 10, finished = 9, longest = 1),
                        entity("c", allTime = 100, last365 = 99, finished = 1, longest = 8),
                    ),
                )
            val repo = LeaderboardRepositoryImpl(FakePublicProfileDao(rows))
            repo.observeSnapshot(LeaderboardPeriod.AllTime, limit = 20).test {
                val snap = awaitItem()
                snap.time.map { it.userId } shouldBe listOf("a", "b", "c")
                snap.time.map { it.rank } shouldBe listOf(1, 1, 3)
                snap.books.first().userId shouldBe "b"
                snap.streak.first().userId shouldBe "c"
                cancelAndIgnoreRemainingEvents()
            }
        }

        test("Year ranking uses last365 for Time, and the 365-day windowed columns for Books/Streak") {
            val rows =
                MutableStateFlow(
                    listOf(
                        entity("a", allTime = 300, last365 = 30, finished = 2, longest = 5, booksW365 = 1, streakW365 = 2),
                        entity("c", allTime = 100, last365 = 99, finished = 1, longest = 8, booksW365 = 4, streakW365 = 7),
                    ),
                )
            val repo = LeaderboardRepositoryImpl(FakePublicProfileDao(rows))
            repo.observeSnapshot(LeaderboardPeriod.Year, limit = 20).test {
                val snap = awaitItem()
                snap.time.map { it.userId } shouldBe listOf("c", "a")
                // Books/Streak now rank by the 365-day windowed columns and carry the windowed value.
                snap.books.map { it.userId } shouldBe listOf("c", "a")
                snap.books.first().booksFinished shouldBe 4
                snap.streak.map { it.userId } shouldBe listOf("c", "a")
                snap.streak.first().longestStreakDays shouldBe 7
                cancelAndIgnoreRemainingEvents()
            }
        }

        test("Week ranking uses totalSecondsLast7Days") {
            val rows =
                MutableStateFlow(
                    listOf(
                        entity("a", allTime = 1000, last7 = 60, last365 = 30, finished = 2, longest = 5, booksW7 = 3, streakW7 = 2),
                        entity("b", allTime = 500, last7 = 120, last365 = 10, finished = 1, longest = 1, booksW7 = 1, streakW7 = 4),
                    ),
                )
            val repo = LeaderboardRepositoryImpl(FakePublicProfileDao(rows))
            repo.observeSnapshot(LeaderboardPeriod.Week, limit = 20).test {
                val snap = awaitItem()
                // b has more last7 seconds
                snap.time.map { it.userId } shouldBe listOf("b", "a")
                snap.time[0].totalSeconds shouldBe 120L
                snap.time[1].totalSeconds shouldBe 60L
                // Books rank by booksFinishedLast7Days (a=3 > b=1); Streak by longestStreakLast7Days (b=4 > a=2).
                snap.books.map { it.userId } shouldBe listOf("a", "b")
                snap.books.first().booksFinished shouldBe 3
                snap.streak.map { it.userId } shouldBe listOf("b", "a")
                snap.streak.first().longestStreakDays shouldBe 4
                cancelAndIgnoreRemainingEvents()
            }
        }

        test("Month ranking uses totalSecondsLast30Days") {
            val rows =
                MutableStateFlow(
                    listOf(
                        entity("x", allTime = 1000, last30 = 200, last365 = 30, finished = 2, longest = 5, booksW30 = 5, streakW30 = 2),
                        entity("y", allTime = 2000, last30 = 50, last365 = 10, finished = 1, longest = 1, booksW30 = 1, streakW30 = 9),
                    ),
                )
            val repo = LeaderboardRepositoryImpl(FakePublicProfileDao(rows))
            repo.observeSnapshot(LeaderboardPeriod.Month, limit = 20).test {
                val snap = awaitItem()
                snap.time.map { it.userId } shouldBe listOf("x", "y")
                snap.time[0].totalSeconds shouldBe 200L
                // Books rank by booksFinishedLast30Days (x=5 > y=1); Streak by longestStreakLast30Days (y=9 > x=2).
                snap.books.map { it.userId } shouldBe listOf("x", "y")
                snap.books.first().booksFinished shouldBe 5
                snap.streak.map { it.userId } shouldBe listOf("y", "x")
                snap.streak.first().longestStreakDays shouldBe 9
                cancelAndIgnoreRemainingEvents()
            }
        }

        test("AllTime dense tie ranking: tied values share rank; next jumps to position") {
            val rows =
                MutableStateFlow(
                    listOf(
                        entity("u1", allTime = 500, last365 = 0, finished = 0, longest = 0),
                        entity("u2", allTime = 500, last365 = 0, finished = 0, longest = 0),
                        entity("u3", allTime = 500, last365 = 0, finished = 0, longest = 0),
                        entity("u4", allTime = 100, last365 = 0, finished = 0, longest = 0),
                    ),
                )
            val repo = LeaderboardRepositoryImpl(FakePublicProfileDao(rows))
            repo.observeSnapshot(LeaderboardPeriod.AllTime, limit = 20).test {
                val snap = awaitItem()
                snap.time.map { it.rank } shouldBe listOf(1, 1, 1, 4)
                cancelAndIgnoreRemainingEvents()
            }
        }

        test("AllTime Time entry totalSeconds reflects allTime value") {
            val rows =
                MutableStateFlow(
                    listOf(
                        entity("a", allTime = 3600, last365 = 100, finished = 1, longest = 2),
                    ),
                )
            val repo = LeaderboardRepositoryImpl(FakePublicProfileDao(rows))
            repo.observeSnapshot(LeaderboardPeriod.AllTime, limit = 20).test {
                val snap = awaitItem()
                snap.time[0].totalSeconds shouldBe 3600L
                cancelAndIgnoreRemainingEvents()
            }
        }

        test("limit caps each category list") {
            val rows =
                MutableStateFlow(
                    (1..10).map { i ->
                        entity("u$i", allTime = (100 - i).toLong(), last365 = 0, finished = i, longest = i)
                    },
                )
            val repo = LeaderboardRepositoryImpl(FakePublicProfileDao(rows))
            repo.observeSnapshot(LeaderboardPeriod.AllTime, limit = 3).test {
                val snap = awaitItem()
                snap.time.size shouldBe 3
                snap.books.size shouldBe 3
                snap.streak.size shouldBe 3
                cancelAndIgnoreRemainingEvents()
            }
        }

        test("empty roster yields empty snapshot") {
            val rows = MutableStateFlow(emptyList<PublicProfileEntity>())
            val repo = LeaderboardRepositoryImpl(FakePublicProfileDao(rows))
            repo.observeSnapshot(LeaderboardPeriod.AllTime, limit = 20).test {
                val snap = awaitItem()
                snap.time shouldBe emptyList()
                snap.books shouldBe emptyList()
                snap.streak shouldBe emptyList()
                cancelAndIgnoreRemainingEvents()
            }
        }

        test("snapshot re-emits when roster changes") {
            val rows =
                MutableStateFlow(
                    listOf(entity("a", allTime = 100, last365 = 0, finished = 1, longest = 1)),
                )
            val repo = LeaderboardRepositoryImpl(FakePublicProfileDao(rows))
            repo.observeSnapshot(LeaderboardPeriod.AllTime, limit = 20).test {
                awaitItem().time.size shouldBe 1
                rows.value = rows.value + entity("b", allTime = 200, last365 = 0, finished = 2, longest = 2)
                val updated = awaitItem()
                updated.time.size shouldBe 2
                updated.time[0].userId shouldBe "b"
                cancelAndIgnoreRemainingEvents()
            }
        }
    })
