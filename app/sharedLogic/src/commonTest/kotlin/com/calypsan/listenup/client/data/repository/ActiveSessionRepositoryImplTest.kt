package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.dto.social.CurrentlyListeningSession
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookSummary
import com.calypsan.listenup.client.data.local.db.CachedActiveSessionDao
import com.calypsan.listenup.client.data.local.db.CachedActiveSessionEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.PRESENCE_POLL_INTERVAL_MS
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.domain.model.ActiveSession
import com.calypsan.listenup.client.domain.repository.ImageStorage
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentiallyReturns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

/**
 * Tests for the offline-first [ActiveSessionRepositoryImpl]. Room's `cached_active_sessions` mirror is
 * the read source; the RPC refresh replaces it on subscribe and on every [PresenceRefreshSignal] ping,
 * and on failure the cache is left intact (Never-Stranded). Book fields are enriched at read time from
 * the local library. The in-memory [FakeCachedActiveSessionDao] stands in for Room in commonTest.
 */
class ActiveSessionRepositoryImplTest :
    FunSpec({

        fun session(
            userId: String,
            bookId: String,
            displayName: String = "User",
            avatarType: String = "auto",
            lastActiveAtMs: Long = 1_000L,
            isLive: Boolean = true,
        ) = CurrentlyListeningSession(
            userId = userId,
            displayName = displayName,
            avatarType = avatarType,
            bookId = bookId,
            lastActiveAtMs = lastActiveAtMs,
            isLive = isLive,
        )

        fun bookDaoReturning(vararg summaries: BookSummary): BookDao {
            val dao = mock<BookDao>(MockMode.autoUnit)
            val byId = summaries.associateBy { it.id }
            everySuspend { dao.getBookSummary(any()) } returns null
            summaries.forEach { summary ->
                everySuspend { dao.getBookSummary(summary.id) } returns byId[summary.id]
            }
            return dao
        }

        fun imageStorage(): ImageStorage {
            val storage = mock<ImageStorage>()
            every { storage.exists(any()) } returns false
            return storage
        }

        fun repo(
            channel: RpcChannel<SocialService>,
            bookDao: BookDao,
            dao: CachedActiveSessionDao,
            presence: PresenceRefreshSignal = PresenceRefreshSignal(),
            images: ImageStorage = imageStorage(),
        ) = ActiveSessionRepositoryImpl(
            channel = channel,
            bookDao = bookDao,
            imageStorage = images,
            presence = presence,
            cachedSessionDao = dao,
        )

        test("maps currently-listening sessions and enriches title/author from local Room") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { currentlyListening() } returns
                            AppResult.Success(listOf(session(userId = "u2", bookId = "bookA", displayName = "Bob")))
                    }
                val bookDao =
                    bookDaoReturning(
                        BookSummary(
                            id = "bookA",
                            title = "The Way of Kings",
                            coverHash = "cover-hash-a",
                            authorName = "Brandon",
                        ),
                    )

                repo(RpcChannel.forTest(service), bookDao, FakeCachedActiveSessionDao())
                    .observeActiveSessions("u1")
                    .test {
                        val s = awaitNonEmpty().first()
                        s.userId shouldBe "u2"
                        s.bookId shouldBe "bookA"
                        s.user.displayName shouldBe "Bob"
                        s.book.title shouldBe "The Way of Kings"
                        s.book.authorName shouldBe "Brandon"
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("drops sessions whose book is absent from the local library") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { currentlyListening() } returns
                            AppResult.Success(
                                listOf(
                                    session(userId = "u2", bookId = "present"),
                                    session(userId = "u3", bookId = "missing"),
                                ),
                            )
                    }
                val bookDao =
                    bookDaoReturning(
                        BookSummary(id = "present", title = "Present", coverHash = null, authorName = null),
                    )

                repo(RpcChannel.forTest(service), bookDao, FakeCachedActiveSessionDao())
                    .observeActiveSessions("u1")
                    .test {
                        val sessions = awaitNonEmpty()
                        sessions.size shouldBe 1
                        sessions.first().bookId shouldBe "present"
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("re-fetches when the presence signal pings") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { currentlyListening() } sequentiallyReturns
                            listOf(
                                AppResult.Success(listOf(session(userId = "u2", bookId = "bookA"))),
                                AppResult.Success(
                                    listOf(session(userId = "u2", bookId = "bookA"), session(userId = "u3", bookId = "bookA")),
                                ),
                            )
                    }
                val bookDao =
                    bookDaoReturning(
                        BookSummary(id = "bookA", title = "A", coverHash = null, authorName = null),
                    )
                val presence = PresenceRefreshSignal()

                repo(RpcChannel.forTest(service), bookDao, FakeCachedActiveSessionDao(), presence = presence)
                    .observeActiveSessions("u1")
                    .test {
                        awaitNonEmpty().size shouldBe 1
                        presence.ping()
                        awaitItem().size shouldBe 2
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("with no ping, advancing past the poll interval re-fetches — a dropped nudge self-heals") {
            runTest {
                val scheduler = testScheduler
                val service =
                    mock<SocialService> {
                        everySuspend { currentlyListening() } sequentiallyReturns
                            listOf(
                                AppResult.Success(listOf(session(userId = "u2", bookId = "bookA"))),
                                AppResult.Success(
                                    listOf(session(userId = "u2", bookId = "bookA"), session(userId = "u3", bookId = "bookA")),
                                ),
                            )
                    }
                val bookDao =
                    bookDaoReturning(
                        BookSummary(id = "bookA", title = "A", coverHash = null, authorName = null),
                    )

                repo(RpcChannel.forTest(service), bookDao, FakeCachedActiveSessionDao())
                    .observeActiveSessions("u1")
                    .test {
                        awaitNonEmpty().size shouldBe 1
                        // No ping fires — only the subscription-scoped backstop poll converges the roster.
                        scheduler.advanceTimeBy(PRESENCE_POLL_INTERVAL_MS + 1)
                        awaitItem().size shouldBe 2
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("the backstop poll stops when the surface is closed — no refetch after the collector leaves") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { currentlyListening() } returns
                            AppResult.Success(listOf(session(userId = "u2", bookId = "bookA")))
                    }
                val bookDao =
                    bookDaoReturning(
                        BookSummary(id = "bookA", title = "A", coverHash = null, authorName = null),
                    )

                repo(RpcChannel.forTest(service), bookDao, FakeCachedActiveSessionDao())
                    .observeActiveSessions("u1")
                    .test {
                        awaitNonEmpty().size shouldBe 1
                        cancelAndIgnoreRemainingEvents()
                    }

                // Surface closed: advance past several poll intervals — the ticker died with the collector.
                testScheduler.advanceTimeBy(PRESENCE_POLL_INTERVAL_MS * 3)
                verifySuspend(exactly(1)) { service.currentlyListening() }
            }
        }

        test("a refresh failure keeps the cached sessions instead of blanking (Never-Stranded)") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { currentlyListening() } sequentiallyReturns
                            listOf(
                                AppResult.Success(listOf(session(userId = "u2", bookId = "bookA"))),
                                AppResult.Failure(TransportError.NetworkUnavailable()),
                            )
                    }
                val bookDao =
                    bookDaoReturning(
                        BookSummary(id = "bookA", title = "A", coverHash = null, authorName = null),
                    )
                val presence = PresenceRefreshSignal()

                repo(RpcChannel.forTest(service), bookDao, FakeCachedActiveSessionDao(), presence = presence)
                    .observeActiveSessions("u1")
                    .test {
                        awaitNonEmpty().size shouldBe 1
                        presence.ping() // triggers the failing refresh
                        expectNoEvents() // cache untouched — still one session
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("carries the live flag and last-active timestamp from the wire through the cache") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { currentlyListening() } returns
                            AppResult.Success(
                                listOf(
                                    session(userId = "u2", bookId = "bookA", isLive = true, lastActiveAtMs = 7_000L),
                                    session(userId = "u3", bookId = "bookA", isLive = false, lastActiveAtMs = 3_000L),
                                ),
                            )
                    }
                val bookDao =
                    bookDaoReturning(
                        BookSummary(id = "bookA", title = "A", coverHash = null, authorName = null),
                    )

                repo(RpcChannel.forTest(service), bookDao, FakeCachedActiveSessionDao())
                    .observeActiveSessions("u1")
                    .test {
                        val sessions = awaitNonEmpty()
                        val live = sessions.first { it.userId == "u2" }
                        val recent = sessions.first { it.userId == "u3" }
                        live.isLive shouldBe true
                        live.lastActiveAtMs shouldBe 7_000L
                        // The recent half is what makes the section render at all on a quiet server;
                        // if its flag or timestamp is dropped here the UI cannot tell the two apart.
                        recent.isLive shouldBe false
                        recent.lastActiveAtMs shouldBe 3_000L
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("reads live rows ahead of recent ones, newest first within each group") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { currentlyListening() } returns
                            AppResult.Success(
                                listOf(
                                    // Deliberately shuffled, and the recent rows carry the LARGER
                                    // timestamps — liveness has to win over raw recency.
                                    session(userId = "recent-older", bookId = "bookA", isLive = false, lastActiveAtMs = 80_000L),
                                    session(userId = "live-older", bookId = "bookA", isLive = true, lastActiveAtMs = 100L),
                                    session(userId = "recent-newer", bookId = "bookA", isLive = false, lastActiveAtMs = 90_000L),
                                    session(userId = "live-newer", bookId = "bookA", isLive = true, lastActiveAtMs = 200L),
                                ),
                            )
                    }
                val bookDao =
                    bookDaoReturning(
                        BookSummary(id = "bookA", title = "A", coverHash = null, authorName = null),
                    )

                repo(RpcChannel.forTest(service), bookDao, FakeCachedActiveSessionDao())
                    .observeActiveSessions("u1")
                    .test {
                        awaitNonEmpty().map { it.userId } shouldBe
                            listOf("live-newer", "live-older", "recent-newer", "recent-older")
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("an empty cache with a failing RPC emits empty gracefully") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { currentlyListening() } returns
                            AppResult.Failure(TransportError.NetworkUnavailable())
                    }

                repo(RpcChannel.forTest(service), bookDaoReturning(), FakeCachedActiveSessionDao())
                    .observeActiveSessions("u1")
                    .test {
                        awaitItem().shouldBeEmpty()
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }
    })

/**
 * In-memory [CachedActiveSessionDao] for commonTest — a single [MutableStateFlow] mirror.
 *
 * [observeAll] applies the real DAO's `ORDER BY isLive DESC, lastActiveAtMs DESC` so a fake read
 * cannot pass an ordering the SQL would fail.
 */
private class FakeCachedActiveSessionDao : CachedActiveSessionDao {
    private val flow = MutableStateFlow<List<CachedActiveSessionEntity>>(emptyList())

    override fun observeAll(): Flow<List<CachedActiveSessionEntity>> = flow.map { rows -> rows.sortedWith(readOrder) }

    override suspend fun upsertAll(rows: List<CachedActiveSessionEntity>) {
        flow.value = flow.value.filter { c -> rows.none { it.userId == c.userId } } + rows
    }

    override suspend fun deleteAll() {
        flow.value = emptyList()
    }

    // Match the real DAO's @Transaction: one atomic replacement, not a delete-then-insert flicker.
    override suspend fun replaceAll(rows: List<CachedActiveSessionEntity>) {
        flow.value = rows
    }

    private companion object {
        /** Mirrors the real DAO's `ORDER BY isLive DESC, lastActiveAtMs DESC`. */
        val readOrder =
            compareByDescending<CachedActiveSessionEntity> { it.isLive }
                .thenByDescending { it.lastActiveAtMs }
    }
}

/** Await the first non-empty sessions emission (skips the initial empty cache emission). */
private suspend fun app.cash.turbine.TurbineTestContext<List<ActiveSession>>.awaitNonEmpty(): List<ActiveSession> {
    var sessions = awaitItem()
    while (sessions.isEmpty()) sessions = awaitItem()
    return sessions
}
