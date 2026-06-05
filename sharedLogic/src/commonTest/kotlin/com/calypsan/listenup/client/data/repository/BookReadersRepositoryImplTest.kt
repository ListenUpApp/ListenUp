package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.dto.social.BookReader
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.SocialRpcFactory
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tests for [BookReadersRepositoryImpl] over the [SocialService] RPC seam.
 *
 * The server already excludes the caller and filters by access, so the repository simply maps
 * the ACL-filtered [BookReader] list to [com.calypsan.listenup.client.domain.readers.Reader],
 * re-fetching on every [PresenceRefreshSignal] ping. A failed RPC yields empty readers
 * (Never-Stranded); [CancellationException] always propagates.
 */
class BookReadersRepositoryImplTest :
    FunSpec({

        fun reader(
            userId: String,
            displayName: String,
        ) = BookReader(userId = userId, displayName = displayName, avatarType = "auto", startedAtMs = 1_000L)

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

        fun repo(
            rpc: SocialRpcFactory,
            presence: PresenceRefreshSignal,
        ) = BookReadersRepositoryImpl(socialRpc = rpc, presence = presence)

        // ── Maps BookReader → Reader ──────────────────────────────────────────

        test("maps book readers to domain Readers") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReaders(BookId("bookA")) } returns
                            AppResult.Success(listOf(reader("u2", "Bob"), reader("u3", "Carol")))
                    }

                repo(fakeRpc(service), PresenceRefreshSignal()).observeReadersFor("bookA").test {
                    val readers = awaitItem()
                    readers.currentlyListening shouldHaveSize 2
                    readers.currentlyListening.map { it.userId } shouldContainExactly listOf("u2", "u3")
                    readers.currentlyListening.map { it.displayName } shouldContainExactly listOf("Bob", "Carol")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── Re-fetches on presence ping ───────────────────────────────────────

        test("re-fetches when the presence signal pings") {
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

                repo(fakeRpc(service), presence).observeReadersFor("bookA").test {
                    awaitItem().currentlyListening shouldHaveSize 1
                    presence.ping()
                    awaitItem().currentlyListening shouldHaveSize 2
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── RPC failure → empty readers ───────────────────────────────────────

        test("RPC failure result yields empty readers") {
            runTest {
                val service =
                    mock<SocialService> {
                        everySuspend { bookReaders(any()) } returns
                            AppResult.Failure(TransportError.NetworkUnavailable())
                    }

                repo(fakeRpc(service), PresenceRefreshSignal()).observeReadersFor("bookA").test {
                    awaitItem().currentlyListening.shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("thrown RPC error yields empty readers") {
            runTest {
                repo(throwingRpc(RuntimeException("boom")), PresenceRefreshSignal())
                    .observeReadersFor("bookA")
                    .test {
                        awaitItem().currentlyListening.shouldBeEmpty()
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        // A swallowed CancellationException would surface as an emitted empty BookReaders;
        // re-throwing cancels the in-flight fetch instead, so no item is emitted in the window.
        test("CancellationException from the RPC is not swallowed into an empty emission") {
            runTest {
                val flow =
                    repo(throwingRpc(CancellationException("cancel")), PresenceRefreshSignal())
                        .observeReadersFor("bookA")

                val emitted = withTimeoutOrNull(100) { flow.first() }
                emitted shouldBe null
            }
        }
    })
