package com.calypsan.listenup.client.campfire

import app.cash.turbine.TurbineTestContext
import app.cash.turbine.test
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireInvitableUser
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.CampfireSnapshot
import com.calypsan.listenup.api.dto.campfire.OpenCampfireSummary
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.sync.CampfireRefreshSignal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

/**
 * TDD suite for [CampfireDiscoveryRepository] (campfire implementation plan, Task 9). Drives the
 * repository against a hand-written [FakeDiscoveryTransport] (mirrors the fake already used by
 * [CampfireSessionControllerTest]) and a real [CampfireRefreshSignal].
 *
 * The cache starts empty and `merge(refreshOnPing(), cache)`'s two branches race — how many
 * "still empty" emissions land before the fetch-on-subscribe result is visible isn't guaranteed,
 * so every assertion below awaits by *content* ([awaitUntil]) rather than a fixed item count, the
 * same robustness [BookReadersRepositoryImplTest]'s `awaitNonEmpty()` helper uses for the
 * structurally identical merge shape.
 */
class CampfireDiscoveryRepositoryTest :
    FunSpec({

        fun summary(
            id: String,
            bookId: String,
        ) = OpenCampfireSummary(
            id = CampfireId(id),
            bookId = bookId,
            hostUserId = "host-1",
            memberCount = 1,
            controlMode = CampfireControlMode.EVERYONE,
            inviteOnly = false,
        )

        test("observeOpenSessions fetches on subscribe (screen entry) with no prior ping") {
            runTest {
                val transport = FakeDiscoveryTransport(listResult = AppResult.Success(listOf(summary("cf-1", "book-1"))))
                val repo = CampfireDiscoveryRepository(transport, CampfireRefreshSignal())

                repo.observeOpenSessions().test {
                    val ids = awaitUntil { it.isNotEmpty() }.map { it.id.value }
                    ids shouldContainExactly listOf("cf-1")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeOpenSessions re-fetches when the refresh signal pings") {
            runTest {
                val signal = CampfireRefreshSignal()
                val transport = FakeDiscoveryTransport(listResult = AppResult.Success(emptyList()))
                val repo = CampfireDiscoveryRepository(transport, signal)

                repo.observeOpenSessions().test {
                    awaitUntil { true } // let the fetch-on-subscribe settle (still empty)

                    transport.listResult = AppResult.Success(listOf(summary("cf-2", "book-2")))
                    signal.ping()
                    val ids = awaitUntil { it.isNotEmpty() }.map { it.id.value }
                    ids shouldContainExactly listOf("cf-2")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a refresh failure keeps the last-known list instead of blanking (Never-Stranded)") {
            runTest {
                val signal = CampfireRefreshSignal()
                val transport = FakeDiscoveryTransport(listResult = AppResult.Success(listOf(summary("cf-1", "book-1"))))
                val repo = CampfireDiscoveryRepository(transport, signal)

                repo.observeOpenSessions().test {
                    awaitUntil { it.isNotEmpty() } // fetch-on-subscribe landed cf-1

                    transport.listResult = AppResult.Failure(TransportError.NetworkUnavailable())
                    signal.ping()
                    expectNoEvents() // the cache is untouched — still showing cf-1, no new emission
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("campfiresForBook filters the roster to the requested book") {
            runTest {
                val transport =
                    FakeDiscoveryTransport(
                        listResult =
                            AppResult.Success(
                                listOf(summary("cf-1", "book-1"), summary("cf-2", "book-2")),
                            ),
                    )
                val repo = CampfireDiscoveryRepository(transport, CampfireRefreshSignal())

                repo.campfiresForBook("book-2").test {
                    val ids = awaitUntil { it.isNotEmpty() }.map { it.id.value }
                    ids shouldContainExactly listOf("cf-2")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

/** Awaits the first emission satisfying [predicate], draining any that don't. */
private suspend fun TurbineTestContext<List<OpenCampfireSummary>>.awaitUntil(
    predicate: (List<OpenCampfireSummary>) -> Boolean,
): List<OpenCampfireSummary> {
    var item = awaitItem()
    while (!predicate(item)) item = awaitItem()
    return item
}

/** Minimal [CampfireTransport] fake exercising only [CampfireTransport.listOpenSessions]. */
private class FakeDiscoveryTransport(
    var listResult: AppResult<List<OpenCampfireSummary>>,
) : CampfireTransport {
    override suspend fun createSession(
        bookId: String,
        settings: CampfireSettings,
    ): AppResult<CampfireSnapshot> = throw NotImplementedError("not exercised by CampfireDiscoveryRepository")

    override suspend fun joinSession(sessionId: CampfireId): AppResult<CampfireSnapshot> = throw NotImplementedError()

    override suspend fun leaveSession(sessionId: CampfireId): AppResult<Unit> = throw NotImplementedError()

    override suspend fun endSession(sessionId: CampfireId): AppResult<Unit> = throw NotImplementedError()

    override suspend fun transferHost(
        sessionId: CampfireId,
        toUserId: String,
    ): AppResult<Unit> = throw NotImplementedError()

    override suspend fun setControlMode(
        sessionId: CampfireId,
        mode: CampfireControlMode,
    ): AppResult<Unit> = throw NotImplementedError()

    override fun observeSession(sessionId: CampfireId): Flow<RpcEvent<CampfireFrame>> = throw NotImplementedError()

    override suspend fun sendCommand(
        sessionId: CampfireId,
        command: PlaybackCommand,
    ): AppResult<Unit> = throw NotImplementedError()

    override suspend fun sendChat(
        sessionId: CampfireId,
        text: String,
    ): AppResult<Unit> = throw NotImplementedError()

    override suspend fun sendReaction(
        sessionId: CampfireId,
        emoji: String,
    ): AppResult<Unit> = throw NotImplementedError()

    override suspend fun listOpenSessions(): AppResult<List<OpenCampfireSummary>> = listResult

    override suspend fun listInvitableUsers(bookId: String): AppResult<List<CampfireInvitableUser>> = throw NotImplementedError()
}
