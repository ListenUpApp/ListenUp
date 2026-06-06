package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.social.BookReader
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.SocialRpcFactory
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.readers.ReaderState
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
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
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tests for [BookReadersRepositoryImpl].
 *
 * The repository combines the current user's own reading state (from the local playback position +
 * profile) with other live listeners from the [SocialService] RPC (ACL-filtered, caller-excluded),
 * re-fetching the others on every [PresenceRefreshSignal] ping. The current user, when reading or
 * finished, is listed first and survives an RPC failure (Never-Stranded); a cancelled fetch always
 * propagates rather than collapsing into an empty emission.
 */
class BookReadersRepositoryImplTest :
    FunSpec({

        fun reader(
            userId: String,
            displayName: String,
        ) = BookReader(userId = userId, displayName = displayName, avatarType = "auto", startedAtMs = 1_000L)

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

        fun position(
            bookId: String = "bookA",
            positionMs: Long = 0L,
            isFinished: Boolean = false,
            finishedAtMs: Long? = null,
        ) = PlaybackPosition(
            bookId = bookId,
            positionMs = positionMs,
            playbackSpeed = 1.0f,
            hasCustomSpeed = false,
            updatedAtMs = 0L,
            syncedAtMs = null,
            lastPlayedAtMs = 0L,
            isFinished = isFinished,
            finishedAtMs = finishedAtMs,
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
            positions: Map<String, PlaybackPosition> = emptyMap(),
            currentUser: User? = user(),
        ) = BookReadersRepositoryImpl(
            socialRpc = rpc,
            presence = presence,
            playbackPositionRepository = FakePlaybackPositionRepository(initialPositions = positions),
            userRepository = fakeUsers(currentUser),
        )

        // ── Other listeners (server) ──────────────────────────────────────────

        test("maps other live listeners to readers when the current user has not started") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReaders(BookId("bookA")) } returns
                            AppResult.Success(listOf(reader("u2", "Bob"), reader("u3", "Carol")))
                    }

                repo(fakeRpc(service)).observeReadersFor("bookA").test {
                    val readers = awaitItem().readers
                    readers.map { it.userId } shouldContainExactly listOf("u2", "u3")
                    readers.all { it.state is ReaderState.Listening } shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── Current user inclusion ────────────────────────────────────────────

        test("current user reading is listed first, ahead of other listeners") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReaders(BookId("bookA")) } returns
                            AppResult.Success(listOf(reader("u2", "Bob")))
                    }

                repo(
                    fakeRpc(service),
                    positions = mapOf("bookA" to position(positionMs = 5_000L)),
                    currentUser = user(id = "me", displayName = "Me"),
                ).observeReadersFor("bookA").test {
                    val readers = awaitItem().readers
                    readers.map { it.userId } shouldContainExactly listOf("me", "u2")
                    readers.first().state.shouldBeInstanceOf<ReaderState.Listening>()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("current user who finished the book shows a Finished reader with the finish date") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReaders(any()) } returns AppResult.Success(emptyList())
                    }

                repo(
                    fakeRpc(service),
                    positions = mapOf("bookA" to position(isFinished = true, finishedAtMs = 1_700_000_000_000L)),
                ).observeReadersFor("bookA").test {
                    val readers = awaitItem().readers
                    readers shouldHaveSize 1
                    val state = readers.single().state
                    state.shouldBeInstanceOf<ReaderState.Finished>()
                    state.finishedAtMs shouldBe 1_700_000_000_000L
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("current user with a zeroed, unfinished position is not a reader") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReaders(any()) } returns AppResult.Success(emptyList())
                    }

                repo(
                    fakeRpc(service),
                    positions = mapOf("bookA" to position(positionMs = 0L)),
                ).observeReadersFor("bookA").test {
                    awaitItem().readers.shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── Re-fetches on presence ping ───────────────────────────────────────

        test("re-fetches other listeners when the presence signal pings") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReaders(BookId("bookA")) } sequentiallyReturns
                            listOf(
                                AppResult.Success(listOf(reader("u2", "Bob"))),
                                AppResult.Success(listOf(reader("u2", "Bob"), reader("u3", "Carol"))),
                            )
                    }
                val presence = PresenceRefreshSignal()

                repo(fakeRpc(service), presence = presence, currentUser = null)
                    .observeReadersFor("bookA")
                    .test {
                        awaitItem().readers shouldHaveSize 1
                        presence.ping()
                        awaitItem().readers shouldHaveSize 2
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        // ── RPC failure → only the current user's own row survives ─────────────

        test("RPC failure still shows the current user's own row") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReaders(any()) } returns
                            AppResult.Failure(TransportError.NetworkUnavailable())
                    }

                repo(
                    fakeRpc(service),
                    positions = mapOf("bookA" to position(positionMs = 5_000L)),
                ).observeReadersFor("bookA").test {
                    awaitItem().readers.map { it.userId } shouldContainExactly listOf("me")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("RPC failure with no current-user reading yields empty readers") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReaders(any()) } returns
                            AppResult.Failure(TransportError.NetworkUnavailable())
                    }

                repo(fakeRpc(service), currentUser = null).observeReadersFor("bookA").test {
                    awaitItem().readers.shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("thrown RPC error with no current-user reading yields empty readers") {
            runTest {
                repo(throwingRpc(RuntimeException("boom")), currentUser = null)
                    .observeReadersFor("bookA")
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
                    repo(throwingRpc(CancellationException("cancel")), currentUser = null)
                        .observeReadersFor("bookA")

                val emitted = withTimeoutOrNull(100) { flow.first() }
                emitted shouldBe null
            }
        }
    })
