package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.dto.social.CurrentlyListeningSession
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookSummary
import com.calypsan.listenup.client.data.remote.SocialRpcFactory
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.domain.repository.ImageStorage
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentiallyReturns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tests for [ActiveSessionRepositoryImpl] over the [SocialService] RPC seam.
 *
 * The repository fetches the ACL-filtered currently-listening list on first subscribe and
 * re-fetches on every [PresenceRefreshSignal] ping, enriching each session's book fields
 * from the local Room library. A failed RPC yields an empty list (Never-Stranded), while
 * [CancellationException] always propagates.
 */
class ActiveSessionRepositoryImplTest :
    FunSpec({

        fun session(
            userId: String,
            bookId: String,
            displayName: String = "User",
            avatarType: String = "auto",
            startedAtMs: Long = 1_000L,
        ) = CurrentlyListeningSession(
            userId = userId,
            displayName = displayName,
            avatarType = avatarType,
            bookId = bookId,
            startedAtMs = startedAtMs,
        )

        fun fakeRpc(service: SocialService): SocialRpcFactory =
            object : SocialRpcFactory {
                override suspend fun get(): SocialService = service

                override suspend fun invalidate() = Unit
            }

        fun throwingRpc(throwable: Throwable): SocialRpcFactory =
            object : SocialRpcFactory {
                override suspend fun get(): SocialService = throw throwable

                override suspend fun invalidate() = Unit
            }

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
            rpc: SocialRpcFactory,
            bookDao: BookDao,
            presence: PresenceRefreshSignal,
            images: ImageStorage = imageStorage(),
        ) = ActiveSessionRepositoryImpl(
            socialRpc = rpc,
            bookDao = bookDao,
            imageStorage = images,
            presence = presence,
        )

        // ── Maps RPC → domain, enriched from local Room ───────────────────────

        test("maps currently-listening sessions and enriches title/author from local Room") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { currentlyListening() } returns
                            AppResult.Success(listOf(session(userId = "u2", bookId = "bookA", displayName = "Bob")))
                        everySuspend { bookReaders(any()) } returns AppResult.Success(emptyList())
                    }
                val bookDao =
                    bookDaoReturning(
                        BookSummary(id = "bookA", title = "The Way of Kings", coverBlurHash = "blur", authorName = "Brandon"),
                    )
                val presence = PresenceRefreshSignal()

                repo(fakeRpc(service), bookDao, presence).observeActiveSessions("u1").test {
                    val sessions = awaitItem()
                    sessions.size shouldBe 1
                    val s = sessions.first()
                    s.userId shouldBe "u2"
                    s.bookId shouldBe "bookA"
                    s.user.displayName shouldBe "Bob"
                    s.book.title shouldBe "The Way of Kings"
                    s.book.coverBlurHash shouldBe "blur"
                    s.book.authorName shouldBe "Brandon"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── Drops sessions whose book is not in the local library ─────────────

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
                        everySuspend { bookReaders(any()) } returns AppResult.Success(emptyList())
                    }
                val bookDao =
                    bookDaoReturning(
                        BookSummary(id = "present", title = "Present", coverBlurHash = null, authorName = null),
                    )

                repo(fakeRpc(service), bookDao, PresenceRefreshSignal()).observeActiveSessions("u1").test {
                    val sessions = awaitItem()
                    sessions.size shouldBe 1
                    sessions.first().bookId shouldBe "present"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── Re-fetches on every presence ping ─────────────────────────────────

        test("re-fetches when the presence signal pings") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { currentlyListening() } sequentiallyReturns
                            listOf(
                                AppResult.Success(listOf(session(userId = "u2", bookId = "bookA"))),
                                AppResult.Success(
                                    listOf(
                                        session(userId = "u2", bookId = "bookA"),
                                        session(userId = "u3", bookId = "bookA"),
                                    ),
                                ),
                            )
                        everySuspend { bookReaders(any()) } returns AppResult.Success(emptyList())
                    }
                val bookDao =
                    bookDaoReturning(
                        BookSummary(id = "bookA", title = "A", coverBlurHash = null, authorName = null),
                    )
                val presence = PresenceRefreshSignal()

                repo(fakeRpc(service), bookDao, presence).observeActiveSessions("u1").test {
                    awaitItem().size shouldBe 1
                    presence.ping()
                    awaitItem().size shouldBe 2
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── RPC failure → empty list (Never-Stranded) ─────────────────────────

        test("RPC failure result yields an empty list") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { currentlyListening() } returns
                            AppResult.Failure(TransportError.NetworkUnavailable())
                        everySuspend { bookReaders(any()) } returns AppResult.Success(emptyList())
                    }

                repo(fakeRpc(service), bookDaoReturning(), PresenceRefreshSignal())
                    .observeActiveSessions("u1")
                    .test {
                        awaitItem().shouldBeEmpty()
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        // ── Thrown RPC → empty list, but CancellationException propagates ─────

        test("thrown RPC error yields an empty list") {
            runTest {
                repo(throwingRpc(RuntimeException("boom")), bookDaoReturning(), PresenceRefreshSignal())
                    .observeActiveSessions("u1")
                    .test {
                        awaitItem().shouldBeEmpty()
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        // A swallowed CancellationException would surface as an emitted empty list; re-throwing
        // cancels the in-flight fetch instead, so no item is emitted within the window.
        test("CancellationException from the RPC is not swallowed into an empty emission") {
            runTest {
                val flow =
                    repo(throwingRpc(CancellationException("cancel")), bookDaoReturning(), PresenceRefreshSignal())
                        .observeActiveSessions("u1")

                val emitted = withTimeoutOrNull(100) { flow.first() }
                emitted shouldBe null
            }
        }
    })
