package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.HardcoverService
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.RpcDispatch
import com.calypsan.listenup.client.data.remote.RpcPolicy
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration

/**
 * [HardcoverRepositoryImpl] over the real [RpcChannel] fold, driven by an in-memory
 * [FakeHardcoverService]. The connection watch is infinite by contract — the settings screen
 * collects it for as long as it shows — so a server error or completion must resubscribe, and the
 * fresh subscription's current-value emit is what heals whatever was missed.
 */
class HardcoverRepositoryImplTest :
    FunSpec({

        val prompt =
            HardcoverLinkPrompt(
                userCode = "ABCD-1234",
                verificationUri = "https://hardcover.app/link",
                verificationUriComplete = "https://hardcover.app/link?code=ABCD-1234",
                expiresAt = 1_700_000_000_000L,
            )

        test("observeConnection passes the server's Data events through, current value first") {
            runTest {
                val service =
                    FakeHardcoverService(
                        subscriptions =
                            listOf(
                                flowOf(
                                    RpcEvent.Data(HardcoverConnection.NotConnected()),
                                    RpcEvent.Data(HardcoverConnection.Linking(prompt)),
                                    RpcEvent.Data(HardcoverConnection.Connected("reader", since = 42L)),
                                ),
                            ),
                    )
                val repository = HardcoverRepositoryImpl(RpcChannel.forTest(service))

                repository.observeConnection().test {
                    awaitItem() shouldBe HardcoverConnection.NotConnected()
                    awaitItem() shouldBe HardcoverConnection.Linking(prompt)
                    awaitItem() shouldBe HardcoverConnection.Connected("reader", since = 42L)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("an Error then Complete resubscribes, and the new subscription's current value arrives") {
            runTest {
                val service =
                    FakeHardcoverService(
                        subscriptions =
                            listOf(
                                flowOf(
                                    RpcEvent.Data(HardcoverConnection.NotConnected()),
                                    RpcEvent.Error(TransportError.NetworkUnavailable()),
                                    RpcEvent.Complete,
                                ),
                                flowOf(RpcEvent.Data(HardcoverConnection.Broken(HardcoverBrokenReason.REVOKED))),
                            ),
                    )
                val repository = HardcoverRepositoryImpl(RpcChannel.forTest(service))

                // Virtual time skips the backoff delay; a stream that died instead would time out.
                repository.observeConnection().test {
                    awaitItem() shouldBe HardcoverConnection.NotConnected()
                    awaitItem() shouldBe HardcoverConnection.Broken(HardcoverBrokenReason.REVOKED)
                    cancelAndIgnoreRemainingEvents()
                }
                service.subscribeCount shouldBe 2
            }
        }

        test("a plain completion also resubscribes — the flow never ends on its own") {
            runTest {
                val service =
                    FakeHardcoverService(
                        subscriptions =
                            listOf(
                                flowOf(RpcEvent.Data(HardcoverConnection.NotConnected(HardcoverLinkFailure.EXPIRED))),
                                flowOf(RpcEvent.Data(HardcoverConnection.NotOffered)),
                            ),
                    )
                val repository = HardcoverRepositoryImpl(RpcChannel.forTest(service))

                repository.observeConnection().test {
                    awaitItem() shouldBe HardcoverConnection.NotConnected(HardcoverLinkFailure.EXPIRED)
                    awaitItem() shouldBe HardcoverConnection.NotOffered
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("startLink passes a Success through") {
            runTest {
                val service = FakeHardcoverService(startLinkResult = AppResult.Success(prompt))
                val repository = HardcoverRepositoryImpl(RpcChannel.forTest(service))

                repository.startLink() shouldBe AppResult.Success(prompt)
            }
        }

        test("startLink passes a business Failure through untouched") {
            runTest {
                val failure = AppResult.Failure(HardcoverError.AlreadyConnected())
                val service = FakeHardcoverService(startLinkResult = failure)
                val repository = HardcoverRepositoryImpl(RpcChannel.forTest(service))

                repository.startLink() shouldBe failure
            }
        }

        test("disconnect passes a Success through") {
            runTest {
                val service = FakeHardcoverService(disconnectResult = AppResult.Success(Unit))
                val repository = HardcoverRepositoryImpl(RpcChannel.forTest(service))

                repository.disconnect() shouldBe AppResult.Success(Unit)
                service.disconnectCount shouldBe 1
            }
        }

        test("disconnect passes a business Failure through untouched") {
            runTest {
                val failure = AppResult.Failure(HardcoverError.Unavailable())
                val service = FakeHardcoverService(disconnectResult = failure)
                val repository = HardcoverRepositoryImpl(RpcChannel.forTest(service))

                repository.disconnect() shouldBe failure
            }
        }

        test("startLink is never re-fired on a lost response; disconnect may be") {
            runTest {
                val dispatch = IdempotenceRecordingDispatch<HardcoverService>(FakeHardcoverService())
                val repository = HardcoverRepositoryImpl(RpcChannel(dispatch, RpcPolicy.Authed))

                // A re-fired startLink would replace the code the user is already typing in.
                repository.startLink()
                dispatch.lastIdempotent shouldBe false

                // Disconnect is idempotent server-side, so a blind retry is safe.
                repository.disconnect()
                dispatch.lastIdempotent shouldBe true
            }
        }

        test("the foreground nudge reaches the server and is a safe blind retry") {
            runTest {
                val service = FakeHardcoverService()
                val dispatch = IdempotenceRecordingDispatch<HardcoverService>(service)
                val repository = HardcoverRepositoryImpl(RpcChannel(dispatch, RpcPolicy.Authed))

                repository.syncIfStale() shouldBe AppResult.Success(Unit)

                service.syncIfStaleCount shouldBe 1
                dispatch.lastIdempotent shouldBe true
            }
        }

        test("a link that lands announces the book; one that fails doesn't") {
            runTest {
                val service = FakeHardcoverService()
                val repository = HardcoverRepositoryImpl(RpcChannel.forTest(service))
                repository.matchChanges.test {
                    repository.linkBook(BookId("b1"), 427_578L, 9_001L) shouldBe AppResult.Success(Unit)
                    awaitItem() shouldBe BookId("b1")
                    service.linkResult = AppResult.Failure(HardcoverError.Unavailable())
                    repository.linkBook(BookId("b2"), 1L, null)
                    repository.unlinkBook(BookId("b3")) shouldBe AppResult.Success(Unit)
                    awaitItem() shouldBe BookId("b3")
                }
                service.linked shouldBe
                    listOf(Triple(BookId("b1"), 427_578L, 9_001L), Triple(BookId("b2"), 1L, null))
            }
        }

        test("the reads and Sync now reach the server and are safe blind retries") {
            runTest {
                val service =
                    FakeHardcoverService().apply {
                        booksNeedingMatchResult = AppResult.Success(listOf(BookId("b1")))
                        bookMatchResult = AppResult.Success(HardcoverBookMatch.NeedsMatch)
                    }
                val dispatch = IdempotenceRecordingDispatch<HardcoverService>(service)
                val repository = HardcoverRepositoryImpl(RpcChannel(dispatch, RpcPolicy.Authed))

                repository.booksNeedingMatch() shouldBe AppResult.Success(listOf(BookId("b1")))
                dispatch.lastIdempotent shouldBe true
                repository.bookMatch(BookId("b1")) shouldBe AppResult.Success(HardcoverBookMatch.NeedsMatch)
                dispatch.lastIdempotent shouldBe true
                repository.searchCatalog("hail mary") shouldBe AppResult.Success(emptyList())
                dispatch.lastIdempotent shouldBe true
                repository.syncNow() shouldBe AppResult.Success(Unit)
                dispatch.lastIdempotent shouldBe true
                service.syncNowCount shouldBe 1
                repository.linkBook(BookId("b1"), 1L, null)
                dispatch.lastIdempotent shouldBe true
            }
        }
    })

/** In-memory [HardcoverService]: each subscribe pops the next scripted stream; unary calls return what they were given. */
private class FakeHardcoverService(
    subscriptions: List<Flow<RpcEvent<HardcoverConnection>>> = emptyList(),
    private val startLinkResult: AppResult<HardcoverLinkPrompt> = AppResult.Failure(HardcoverError.NotConfigured()),
    private val disconnectResult: AppResult<Unit> = AppResult.Success(Unit),
) : HardcoverService {
    private val remainingSubscriptions = subscriptions.toMutableList()
    var subscribeCount = 0
        private set
    var disconnectCount = 0
        private set

    override suspend fun startLink(): AppResult<HardcoverLinkPrompt> = startLinkResult

    override fun observeConnection(): Flow<RpcEvent<HardcoverConnection>> {
        subscribeCount++
        return remainingSubscriptions.removeFirstOrNull() ?: emptyFlow()
    }

    override suspend fun disconnect(): AppResult<Unit> {
        disconnectCount++
        return disconnectResult
    }

    var searchResult: AppResult<List<HardcoverBookCandidate>> = AppResult.Success(emptyList())
    var linkResult: AppResult<Unit> = AppResult.Success(Unit)
    var unlinkResult: AppResult<Unit> = AppResult.Success(Unit)
    var booksNeedingMatchResult: AppResult<List<BookId>> = AppResult.Success(emptyList())
    var bookMatchResult: AppResult<HardcoverBookMatch> = AppResult.Success(HardcoverBookMatch.Unmatched)
    var syncNowCount = 0
        private set
    val linked = mutableListOf<Triple<BookId, Long, Long?>>()

    override suspend fun searchCatalog(query: String): AppResult<List<HardcoverBookCandidate>> = searchResult

    override suspend fun linkBook(
        bookId: BookId,
        hcBookId: Long,
        hcEditionId: Long?,
    ): AppResult<Unit> {
        linked += Triple(bookId, hcBookId, hcEditionId)
        return linkResult
    }

    override suspend fun unlinkBook(bookId: BookId): AppResult<Unit> = unlinkResult

    override suspend fun syncNow(): AppResult<Unit> {
        syncNowCount++
        return AppResult.Success(Unit)
    }

    override suspend fun booksNeedingMatch(): AppResult<List<BookId>> = booksNeedingMatchResult

    override suspend fun bookMatch(bookId: BookId): AppResult<HardcoverBookMatch> = bookMatchResult

    var syncIfStaleCount = 0
        private set

    override suspend fun syncIfStale(): AppResult<Unit> {
        syncIfStaleCount++
        return AppResult.Success(Unit)
    }
}

/** Records the [idempotent] flag each unary call was dispatched with, then delegates to the service. */
private class IdempotenceRecordingDispatch<S : Any>(
    private val service: S,
) : RpcDispatch<S> {
    var lastIdempotent: Boolean? = null

    override suspend fun <R> call(
        timeout: Duration,
        idempotent: Boolean,
        block: suspend (S) -> R,
    ): R {
        lastIdempotent = idempotent
        return block(service)
    }

    override fun <R> streaming(subscribe: suspend (S) -> Flow<R>): Flow<R> = emptyFlow()

    override suspend fun invalidate() = Unit

    override suspend fun retire() = Unit
}
