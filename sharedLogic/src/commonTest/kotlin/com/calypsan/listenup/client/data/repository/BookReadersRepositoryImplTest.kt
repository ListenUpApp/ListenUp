package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.social.BookReaderEntry
import com.calypsan.listenup.api.dto.social.BookReadership
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.SocialRpcFactory
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentiallyReturns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tests for [BookReadersRepositoryImpl].
 *
 * The repository maps the ACL-filtered [SocialService] `bookReadership` RPC — which includes the
 * caller — straight to domain [com.calypsan.listenup.client.domain.readers.Reader]s, flagging the
 * current user via `isYou`, re-fetching on every [PresenceRefreshSignal] ping. On RPC failure it
 * yields an empty list (Never-Stranded); a cancelled fetch always propagates rather than collapsing
 * into an empty emission.
 */
class BookReadersRepositoryImplTest :
    FunSpec({

        fun entry(
            userId: String,
            displayName: String,
            currentProgressPct: Int? = null,
            finishes: List<Long> = emptyList(),
        ) = BookReaderEntry(
            userId = userId,
            displayName = displayName,
            avatarType = "auto",
            currentProgressPct = currentProgressPct,
            finishes = finishes,
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

        fun fakeUsers(current: User?): UserRepository =
            object : UserRepository {
                override fun observeCurrentUser(): Flow<User?> = flowOf(current)

                override fun observeIsAdmin(): Flow<Boolean> = flowOf(current?.isAdmin ?: false)

                override suspend fun getCurrentUser(): User? = current

                override suspend fun saveUser(user: User) = Unit

                override suspend fun clearUsers() = Unit

                override suspend fun refreshCurrentUser(): User? = current
            }

        fun repo(
            rpc: SocialRpcFactory,
            presence: PresenceRefreshSignal = PresenceRefreshSignal(),
            currentUser: User? = user(),
        ) = BookReadersRepositoryImpl(
            socialRpc = rpc,
            presence = presence,
            userRepository = fakeUsers(currentUser),
        )

        // ── Mapping ───────────────────────────────────────────────────────────

        test("maps bookReadership entries to domain readers and flags the current user") {
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

                repo(fakeRpc(service), currentUser = user(id = "me")).observeReadersFor("b1").test {
                    val readers = awaitItem().readers
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

        test("maps readers in server order, preserving entries with no current user") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReadership(BookId("b1")) } returns
                            AppResult.Success(
                                BookReadership(listOf(entry("u2", "Bob"), entry("u3", "Carol"))),
                            )
                    }

                repo(fakeRpc(service), currentUser = null).observeReadersFor("b1").test {
                    val readers = awaitItem().readers
                    readers.map { it.userId } shouldContainExactly listOf("u2", "u3")
                    readers.all { !it.isYou } shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── Re-fetches on presence ping ───────────────────────────────────────

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

                repo(fakeRpc(service), presence = presence, currentUser = null)
                    .observeReadersFor("b1")
                    .test {
                        awaitItem().readers shouldHaveSize 1
                        presence.ping()
                        awaitItem().readers shouldHaveSize 2
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        // ── RPC failure → empty (Never-Stranded) ──────────────────────────────

        test("RPC failure yields empty readers") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReadership(any()) } returns
                            AppResult.Failure(TransportError.NetworkUnavailable())
                    }

                repo(fakeRpc(service)).observeReadersFor("b1").test {
                    awaitItem().readers.shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("thrown RPC error yields empty readers") {
            runTest {
                repo(throwingRpc(RuntimeException("boom")))
                    .observeReadersFor("b1")
                    .test {
                        awaitItem().readers.shouldBeEmpty()
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        // A swallowed CancellationException would surface as an emitted (empty) readers list;
        // re-throwing cancels the in-flight fetch instead, so no item is emitted in the window.
        test("CancellationException from the RPC is not swallowed into an emission") {
            runTest {
                val flow =
                    repo(throwingRpc(CancellationException("cancel")))
                        .observeReadersFor("b1")

                val emitted = withTimeoutOrNull(100) { flow.first() }
                emitted shouldBe null
            }
        }
    })
