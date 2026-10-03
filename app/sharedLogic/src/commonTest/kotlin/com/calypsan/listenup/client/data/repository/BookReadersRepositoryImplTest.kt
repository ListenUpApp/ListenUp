package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.social.BookReaderEntry
import com.calypsan.listenup.api.dto.social.BookReadership
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookRatingAverageRow
import com.calypsan.listenup.client.data.local.db.BookRatingDao
import com.calypsan.listenup.client.data.local.db.BookRatingEntity
import com.calypsan.listenup.client.data.local.db.BookReadershipDao
import com.calypsan.listenup.client.data.local.db.BookReadershipEntity
import com.calypsan.listenup.client.data.local.db.IdRevision
import com.calypsan.listenup.client.data.local.db.PublicProfileDao
import com.calypsan.listenup.client.data.local.db.PublicProfileEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.PRESENCE_POLL_INTERVAL_MS
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.test.SimulatedFailure
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentiallyReturns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest

/**
 * Tests for the offline-first [BookReadersRepositoryImpl]. Room's `book_readership` mirror is the read
 * source: the RPC refresh replaces a book's cached rows on subscribe and on every [PresenceRefreshSignal]
 * ping, and on failure the cache is left intact (Never-Stranded) rather than blanking. The in-memory
 * [FakeBookReadershipDao] stands in for Room in commonTest.
 */
class BookReadersRepositoryImplTest :
    FunSpec({

        fun entry(
            userId: String,
            displayName: String,
            currentProgressPct: Int? = null,
            finishes: List<Long> = emptyList(),
            hardcoverFinishes: List<Long> = emptyList(),
            finishesAlsoOnHardcover: List<Long> = emptyList(),
        ) = BookReaderEntry(
            userId = userId,
            displayName = displayName,
            avatarType = "auto",
            currentProgressPct = currentProgressPct,
            finishes = finishes,
            hardcoverFinishes = hardcoverFinishes,
            finishesAlsoOnHardcover = finishesAlsoOnHardcover,
        )

        fun user(
            id: String = "me",
            displayName: String = "Me",
        ) = User(
            id = UserId(id),
            email = "$id@example.com",
            displayName = displayName,
            isAdmin = false,
            createdAtMs = 0L,
            updatedAtMs = 0L,
        )

        fun fakeUsers(current: User?): UserRepository =
            object : UserRepository {
                override fun observeCurrentUser(): Flow<User?> = flowOf(current)

                override fun observeIsAdmin(): Flow<Boolean> = flowOf(current?.isAdmin ?: false)

                override suspend fun getCurrentUser(): User? = current

                override suspend fun saveUser(user: User) = Unit

                override suspend fun clearUsers() = Unit

                override suspend fun refreshCurrentUser(): User? = current
            }

        fun rating(
            bookId: String,
            userId: String,
            halfStars: Int,
            note: String? = null,
        ) = BookRatingEntity(
            bookId = bookId,
            userId = userId,
            syncId = "$bookId:$userId",
            halfStars = halfStars,
            note = note,
            ratedAt = 1L,
            updatedAt = 1L,
        )

        fun profile(
            id: String,
            displayName: String,
        ) = PublicProfileEntity(
            id = id,
            displayName = displayName,
            avatarType = "auto",
            totalSecondsAllTime = 0,
            totalSecondsLast7Days = 0,
            totalSecondsLast30Days = 0,
            totalSecondsLast365Days = 0,
            booksFinished = 0,
            currentStreakDays = 0,
            longestStreakDays = 0,
        )

        fun repo(
            channel: RpcChannel<SocialService>,
            dao: BookReadershipDao,
            presence: PresenceRefreshSignal = PresenceRefreshSignal(),
            currentUser: User? = user(),
            ratingDao: BookRatingDao = FakeBookRatingDao(),
            profileDao: PublicProfileDao = FakeReaderPublicProfileDao(),
        ) = BookReadersRepositoryImpl(
            channel = channel,
            presence = presence,
            userRepository = fakeUsers(currentUser),
            readershipDao = dao,
            ratingDao = ratingDao,
            publicProfileDao = profileDao,
        )

        test("maps a fetched readership to domain readers and flags the current user") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReadership(BookId("b1")) } returns
                            AppResult.Success(
                                BookReadership(
                                    listOf(
                                        entry("me", "You", currentProgressPct = null, finishes = listOf(300L, 100L)),
                                        entry("u2", "Jake", currentProgressPct = 43, finishes = emptyList()),
                                    ),
                                ),
                            )
                    }

                repo(RpcChannel.forTest(service), FakeBookReadershipDao(), currentUser = user(id = "me"))
                    .observeReadersFor("b1")
                    .test {
                        // First emission is the empty cache; the refresh then fills it.
                        val readers = awaitNonEmpty()
                        readers.first { it.userId == "me" }.let {
                            it.isYou shouldBe true
                            it.finishes shouldBe listOf(300L, 100L)
                            it.currentProgressPct shouldBe null
                        }
                        readers.first { it.userId == "u2" }.let {
                            it.isYou shouldBe false
                            it.currentProgressPct shouldBe 43
                            it.finishes.shouldBeEmpty()
                        }
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("a reader's Hardcover reads survive the Room cache, apart from their ListenUp finishes") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReadership(BookId("b1")) } returns
                            AppResult.Success(
                                BookReadership(
                                    listOf(entry("u2", "Jake", finishes = listOf(300L), hardcoverFinishes = listOf(900L, 100L))),
                                ),
                            )
                    }

                repo(RpcChannel.forTest(service), FakeBookReadershipDao(), currentUser = user(id = "me"))
                    .observeReadersFor("b1")
                    .test {
                        val jake = awaitNonEmpty().single()
                        jake.finishes shouldBe listOf(300L)
                        jake.hardcoverFinishes shouldBe listOf(900L, 100L)
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("which finishes were also logged on Hardcover survives the Room cache") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReadership(BookId("b1")) } returns
                            AppResult.Success(
                                BookReadership(
                                    listOf(
                                        entry(
                                            "u2",
                                            "Jake",
                                            finishes = listOf(900L, 300L),
                                            finishesAlsoOnHardcover = listOf(900L),
                                        ),
                                    ),
                                ),
                            )
                    }

                repo(RpcChannel.forTest(service), FakeBookReadershipDao(), currentUser = user(id = "me"))
                    .observeReadersFor("b1")
                    .test {
                        val jake = awaitNonEmpty().single()
                        jake.finishes shouldBe listOf(900L, 300L)
                        jake.finishesAlsoOnHardcover shouldBe listOf(900L)
                        jake.hardcoverFinishes shouldBe emptyList()
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("preserves entries with no current user") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReadership(BookId("b1")) } returns
                            AppResult.Success(BookReadership(listOf(entry("u2", "Bob"), entry("u3", "Carol"))))
                    }

                repo(RpcChannel.forTest(service), FakeBookReadershipDao(), currentUser = null)
                    .observeReadersFor("b1")
                    .test {
                        val readers = awaitNonEmpty()
                        readers.map { it.userId } shouldContainExactly listOf("u2", "u3")
                        readers.all { !it.isYou } shouldBe true
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("re-fetches readership when the presence signal pings") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReadership(BookId("b1")) } sequentiallyReturns
                            listOf(
                                AppResult.Success(BookReadership(listOf(entry("u2", "Bob")))),
                                AppResult.Success(BookReadership(listOf(entry("u2", "Bob"), entry("u3", "Carol")))),
                            )
                    }
                val presence = PresenceRefreshSignal()

                repo(RpcChannel.forTest(service), FakeBookReadershipDao(), presence = presence, currentUser = null)
                    .observeReadersFor("b1")
                    .test {
                        awaitNonEmpty() shouldHaveSize 1
                        presence.ping()
                        awaitItem().readers shouldHaveSize 2
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("with no ping, advancing past the poll interval re-fetches readership — a dropped nudge self-heals") {
            runTest {
                val scheduler = testScheduler
                val service =
                    mock<SocialService> {
                        everySuspend { bookReadership(BookId("b1")) } sequentiallyReturns
                            listOf(
                                AppResult.Success(BookReadership(listOf(entry("u2", "Bob")))),
                                AppResult.Success(BookReadership(listOf(entry("u2", "Bob"), entry("u3", "Carol")))),
                            )
                    }

                repo(RpcChannel.forTest(service), FakeBookReadershipDao(), currentUser = null)
                    .observeReadersFor("b1")
                    .test {
                        awaitNonEmpty() shouldHaveSize 1
                        // No ping fires — only the subscription-scoped backstop poll converges the readership.
                        scheduler.advanceTimeBy(PRESENCE_POLL_INTERVAL_MS + 1)
                        awaitItem().readers shouldHaveSize 2
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("the backstop poll stops when the surface is closed — no refetch after the collector leaves") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReadership(BookId("b1")) } returns
                            AppResult.Success(BookReadership(listOf(entry("u2", "Bob"))))
                    }

                repo(RpcChannel.forTest(service), FakeBookReadershipDao(), currentUser = null)
                    .observeReadersFor("b1")
                    .test {
                        awaitNonEmpty() shouldHaveSize 1
                        cancelAndIgnoreRemainingEvents()
                    }

                // Surface closed: advance past several poll intervals — the ticker died with the collector.
                testScheduler.advanceTimeBy(PRESENCE_POLL_INTERVAL_MS * 3)
                verifySuspend(exactly(1)) { service.bookReadership(BookId("b1")) }
            }
        }

        test("a refresh failure keeps the cached readers instead of blanking (Never-Stranded)") {
            runTest {
                // Prior successful fetch, then the RPC starts failing.
                val service =
                    mock<SocialService> {
                        everySuspend { bookReadership(BookId("b1")) } sequentiallyReturns
                            listOf(
                                AppResult.Success(BookReadership(listOf(entry("u2", "Bob")))),
                                AppResult.Failure(TransportError.NetworkUnavailable()),
                            )
                    }
                val presence = PresenceRefreshSignal()

                repo(RpcChannel.forTest(service), FakeBookReadershipDao(), presence = presence, currentUser = null)
                    .observeReadersFor("b1")
                    .test {
                        awaitNonEmpty().map { it.userId } shouldContainExactly listOf("u2")
                        presence.ping() // triggers the failing refresh
                        // No new emission — the cache is untouched, still showing Bob.
                        expectNoEvents()
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("a DAO failure during refresh does not kill the readers flow") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReadership(BookId("b1")) } sequentiallyReturns
                            listOf(
                                AppResult.Success(BookReadership(listOf(entry("u2", "Bob")))),
                                AppResult.Success(BookReadership(listOf(entry("u2", "Bob"), entry("u3", "Carol")))),
                            )
                    }
                val presence = PresenceRefreshSignal()

                repo(RpcChannel.forTest(service), ThrowOnceBookReadershipDao(), presence = presence, currentUser = null)
                    .observeReadersFor("b1")
                    .test {
                        // First refresh's DAO write throws — the guard swallows it, cache stays empty.
                        awaitItem().readers.shouldBeEmpty()
                        presence.ping() // the retry refresh's DAO write succeeds
                        awaitNonEmpty() shouldHaveSize 2
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("an empty cache with a failing RPC emits empty gracefully") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReadership(any()) } returns
                            AppResult.Failure(TransportError.NetworkUnavailable())
                    }

                repo(RpcChannel.forTest(service), FakeBookReadershipDao())
                    .observeReadersFor("b1")
                    .test {
                        awaitItem().readers.shouldBeEmpty()
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("a cached reader gains their rating from Room") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReadership(BookId("b1")) } returns
                            AppResult.Success(BookReadership(listOf(entry("u2", "Jake", currentProgressPct = 43))))
                    }
                val ratingDao = FakeBookRatingDao()
                ratingDao.upsert(rating(bookId = "b1", userId = "u2", halfStars = 8, note = "Superb."))

                repo(RpcChannel.forTest(service), FakeBookReadershipDao(), ratingDao = ratingDao, currentUser = null)
                    .observeReadersFor("b1")
                    .test {
                        val jake = awaitNonEmpty().first { it.userId == "u2" }
                        jake.rating.shouldNotBeNull()
                        jake.rating?.halfStars shouldBe 8
                        jake.rating?.note shouldBe "Superb."
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("a rater who isn't in the readership snapshot appears with their public-profile name") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReadership(BookId("b1")) } returns
                            AppResult.Success(BookReadership(emptyList()))
                    }
                val ratingDao = FakeBookRatingDao()
                ratingDao.upsert(rating(bookId = "b1", userId = "u9", halfStars = 6))
                val profileDao = FakeReaderPublicProfileDao()
                profileDao.upsert(profile(id = "u9", displayName = "Nadia"))

                repo(
                    RpcChannel.forTest(service),
                    FakeBookReadershipDao(),
                    ratingDao = ratingDao,
                    profileDao = profileDao,
                    currentUser = null,
                ).observeReadersFor("b1")
                    .test {
                        val nadia = awaitNonEmpty().first { it.userId == "u9" }
                        nadia.displayName shouldBe "Nadia"
                        nadia.isYou shouldBe false
                        nadia.rating?.halfStars shouldBe 6
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }
    })

/** In-memory [BookReadershipDao] for commonTest — a per-book [MutableStateFlow] mirror. */
private class FakeBookReadershipDao : BookReadershipDao {
    private val byBook = mutableMapOf<String, MutableStateFlow<List<BookReadershipEntity>>>()

    private fun flowFor(bookId: String) = byBook.getOrPut(bookId) { MutableStateFlow(emptyList()) }

    override fun observeForBook(bookId: String): Flow<List<BookReadershipEntity>> = flowFor(bookId)

    override suspend fun upsertAll(rows: List<BookReadershipEntity>) {
        rows.groupBy { it.bookId }.forEach { (bookId, bookRows) ->
            flowFor(bookId).update { existing ->
                existing.filter { e -> bookRows.none { it.userId == e.userId } } + bookRows
            }
        }
    }

    override suspend fun deleteForBook(bookId: String) {
        flowFor(bookId).value = emptyList()
    }

    // No books table in this fake — the book-tombstone sweep is exercised at the sync-domain seam,
    // not here. This repository test never triggers it, so a no-op keeps the interface satisfied.
    override suspend fun deleteWhereBookNotLive() = Unit

    override suspend fun deleteAll() {
        byBook.keys.forEach { flowFor(it).value = emptyList() }
    }

    // Match the real DAO's @Transaction: one atomic replacement, not a delete-then-insert flicker.
    override suspend fun replaceForBook(
        bookId: String,
        rows: List<BookReadershipEntity>,
    ) {
        flowFor(bookId).value = rows
    }
}

/** [FakeBookReadershipDao] whose first [replaceForBook] throws — a transient storage fault. */
private class ThrowOnceBookReadershipDao(
    private val delegate: FakeBookReadershipDao = FakeBookReadershipDao(),
) : BookReadershipDao by delegate {
    var thrown = false

    override suspend fun replaceForBook(
        bookId: String,
        rows: List<BookReadershipEntity>,
    ) {
        if (!thrown) {
            thrown = true
            throw SimulatedFailure("simulated storage failure")
        }
        delegate.replaceForBook(bookId, rows)
    }
}

/** Await the first non-empty readers emission (skips the initial empty cache emission). */
private suspend fun app.cash.turbine.TurbineTestContext<com.calypsan.listenup.client.domain.readers.BookReaders>.awaitNonEmpty() =
    run {
        var readers = awaitItem().readers
        while (readers.isEmpty()) readers = awaitItem().readers
        readers
    }

/** In-memory [BookRatingDao] for commonTest — a per-book [MutableStateFlow] mirror, live rows only. */
private class FakeBookRatingDao : BookRatingDao {
    private val byBook = mutableMapOf<String, MutableStateFlow<List<BookRatingEntity>>>()

    private fun flowFor(bookId: String) = byBook.getOrPut(bookId) { MutableStateFlow(emptyList()) }

    override suspend fun upsert(entity: BookRatingEntity) {
        flowFor(entity.bookId).update { existing -> existing.filter { it.userId != entity.userId } + entity }
    }

    override suspend fun find(
        bookId: String,
        userId: String,
    ): BookRatingEntity? = flowFor(bookId).value.firstOrNull { it.userId == userId }

    override fun observeForBook(bookId: String): Flow<List<BookRatingEntity>> =
        flowFor(bookId).map { rows -> rows.filter { it.deletedAt == null } }

    override fun observeAverages(): Flow<List<BookRatingAverageRow>> = error("unused")

    override suspend fun tombstone(
        bookId: String,
        userId: String,
        deletedAt: Long,
    ) = error("unused")

    override suspend fun tombstoneBySyncId(
        syncId: String,
        deletedAt: Long,
    ): Int = error("unused")

    override suspend fun liveIds(): List<String> = error("unused")

    override suspend fun liveSyncIdsForBooks(bookIds: List<String>): List<String> = error("unused")

    override suspend fun tombstoneByIds(
        ids: List<String>,
        now: Long,
    ) = error("unused")

    override suspend fun digestRows(max: Long): List<IdRevision> = error("unused")

    override suspend fun revisionOfSyncId(syncId: String): Long? = error("unused")

    override suspend fun deleteAll() = error("unused")
}

/** In-memory [PublicProfileDao] for commonTest — a single [MutableStateFlow] roster, live rows only. */
private class FakeReaderPublicProfileDao : PublicProfileDao {
    private val flow = MutableStateFlow<List<PublicProfileEntity>>(emptyList())

    override fun observeAll(): Flow<List<PublicProfileEntity>> = flow

    override fun observeById(userId: String): Flow<PublicProfileEntity?> = error("unused")

    override suspend fun upsert(entity: PublicProfileEntity) {
        flow.update { existing -> existing.filter { it.id != entity.id } + entity }
    }

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
