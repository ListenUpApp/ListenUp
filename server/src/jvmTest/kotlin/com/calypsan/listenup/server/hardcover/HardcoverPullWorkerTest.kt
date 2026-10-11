package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.BookRatingRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val USER = "u1"
private const val T0 = 1_779_451_200_000L

private class PullWorkerRig(
    dbs: SqlTestDatabases,
    refreshStatus: HttpStatusCode,
) {
    val sql = dbs.sql
    val clock = MutableClock(Instant.fromEpochMilliseconds(T0))
    val hardcover = FakeHardcoverLibrary()
    private val graphQl = hardcover.client()
    val refreshes = AtomicInteger()
    private val oauth =
        HardcoverOAuthClient(
            HttpClient(
                MockEngine {
                    refreshes.incrementAndGet()
                    respond(
                        """{"access_token":"hc_at_2","refresh_token":"hc_rt_2","expires_in":604800,"scope":"$HARDCOVER_SCOPES"}""",
                        refreshStatus,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
            "listenup-test",
            "https://hc.test",
        )
    val connections = HardcoverConnectionStore(sql, HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("secret")), clock)
    private val linker = HardcoverLinker(oauth, graphQl, connections, CoroutineScope(Dispatchers.Unconfined), clock)
    val store = HardcoverPullStore(sql, clock)
    val links = HardcoverBookLinkStore(sql, clock)
    val gate = HardcoverUserGate()
    val pushNudges = CopyOnWriteArrayList<String>()
    val activity = HardcoverSyncActivity()
    val worker =
        HardcoverPullWorker(
            puller =
                HardcoverPuller(
                    userBooks = HardcoverUserBooks(graphQl),
                    store = store,
                    resolver = HardcoverShelfResolver(sql, BookAccessPolicy(sql, dbs.driver), HardcoverExclusions(sql)),
                    links = links,
                    wantToRead = testWantToRead(dbs, clock),
                    ratingImport =
                        HardcoverRatingImport(
                            sql,
                            BookRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = dbs.driver),
                        ),
                    rateLimiter = NoWaitRateLimiter(),
                    sql = sql,
                    clock = clock,
                ),
            store = store,
            tokens = HardcoverTokenProvider(oauth, connections, linker, clock),
            connections = connections,
            linker = linker,
            gate = gate,
            pushNudge = HardcoverPushNudge { pushNudges += it },
            clock = clock,
            activity = activity,
        )

    init {
        sql.seedTestUser(USER, userRole = UserRoleColumn.ROOT)
        sql.seedTestLibraryAndFolder()
    }

    suspend fun connect() =
        connections.save(USER, HardcoverMe(42, "reader"), HardcoverTokens("hc_at_1", "hc_rt_1", 604_800, HARDCOVER_SCOPES))

    fun pulls() = hardcover.operations.count { it == "user_books_changed" }

    fun lastAfter(): String =
        hardcover.requests
            .last { it.operation == "user_books_changed" }
            .variables
            .getValue("after")
            .jsonPrimitive
            .content

    fun at(ms: Long) {
        clock.instant = Instant.fromEpochMilliseconds(ms)
    }

    fun row() = sql.hardcoverConnectionsQueries.selectByUser(USER).executeAsOne()
}

private fun pullWorkerTest(
    refreshStatus: HttpStatusCode = HttpStatusCode.OK,
    block: suspend PullWorkerRig.() -> Unit,
) = withSqlDatabase { runTest { PullWorkerRig(this@withSqlDatabase, refreshStatus).block() } }

private val INTERVAL_MS = PULL_INTERVAL.inWholeMilliseconds

/** Spec B3's "every 15 minutes per connected user", on its own lane, with a policy for every failure. */
class HardcoverPullWorkerTest :
    FunSpec({

        test("a connected user's first step pulls, stamps the sync, and sleeps until the next pull is due") {
            pullWorkerTest {
                connect()
                worker.step(USER) shouldBe LaneStep.Sleep(T0 + INTERVAL_MS)
                pulls() shouldBe 1
                row().last_synced_at shouldBe T0
            }
        }

        test("before the next pull is due the lane sleeps without asking Hardcover") {
            pullWorkerTest {
                connect()
                worker.step(USER)
                at(T0 + INTERVAL_MS - 1)
                worker.step(USER) shouldBe LaneStep.Sleep(T0 + INTERVAL_MS)
                pulls() shouldBe 1
                at(T0 + INTERVAL_MS)
                worker.step(USER) shouldBe LaneStep.Sleep(T0 + 2 * INTERVAL_MS)
                pulls() shouldBe 2
            }
        }

        test("a long shelf is one page per step") {
            pullWorkerTest {
                connect()
                (1L..PULL_PAGE_SIZE.toLong() + 1).forEach { hardcover.seedShelf(it, HardcoverStatus.READ, null to null) }
                worker.step(USER) shouldBe LaneStep.Continue
                worker.step(USER) shouldBe LaneStep.Sleep(T0 + INTERVAL_MS)
                pulls() shouldBe 2
            }
        }

        test("429 honours Retry-After and pauses push too") {
            pullWorkerTest {
                connect()
                hardcover.failNext(FakeReply(HttpStatusCode.TooManyRequests, headers = mapOf("Retry-After" to "120")))
                worker.step(USER) shouldBe LaneStep.Sleep(T0 + 120_000L)
                gate.pausedUntil(USER) shouldBe T0 + 120_000L
                worker.step(USER) shouldBe LaneStep.Sleep(T0 + 120_000L)
                pulls() shouldBe 1
            }
        }

        test("a pull takes its turn behind the user's other Hardcover conversation, and honours push's pause") {
            pullWorkerTest {
                connect()
                coroutineScope {
                    val held = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    launch {
                        gate.withUser(USER) {
                            held.complete(Unit)
                            release.await()
                        }
                    }
                    held.await()
                    val stepping = async { worker.step(USER) }
                    // Hardcover is answered off the test dispatcher, so give a gate-less step real time to reach it.
                    withContext(Dispatchers.Default) { delay(500.milliseconds) }
                    pulls() shouldBe 0
                    stepping.isCompleted shouldBe false
                    release.complete(Unit)
                    stepping.await() shouldBe LaneStep.Sleep(T0 + INTERVAL_MS)
                    pulls() shouldBe 1
                }

                // A 429 answered to push holds the pull too.
                at(T0 + INTERVAL_MS)
                gate.pause(USER, T0 + INTERVAL_MS + 120_000L)
                worker.step(USER) shouldBe LaneStep.Sleep(T0 + INTERVAL_MS + 120_000L)
                pulls() shouldBe 1
            }
        }

        test("a failing pull backs off from a minute, and past the cap shows its error on the connection") {
            pullWorkerTest {
                connect()
                hardcover.failNext(FakeReply(HttpStatusCode.InternalServerError))
                worker.step(USER) shouldBe LaneStep.Sleep(T0 + 60_000L)
                row().pull_error shouldBe null

                var wake = T0 + 60_000L
                repeat(PULL_MAX_ATTEMPTS - 1) {
                    at(wake)
                    hardcover.failNext(FakeReply(HttpStatusCode.InternalServerError))
                    wake = worker.step(USER).shouldBeInstanceOf<LaneStep.Sleep>().untilMs
                }
                row().pull_error shouldBe "changedSince 500"

                // The next pull that lands clears it.
                at(wake)
                worker.step(USER) shouldBe LaneStep.Sleep(wake + INTERVAL_MS)
                row().pull_error shouldBe null
            }
        }

        test("401 refreshes once and the pull lands") {
            pullWorkerTest {
                connect()
                hardcover.failNext(FakeReply(HttpStatusCode.Unauthorized, """{"error":"invalid_token"}"""))
                worker.step(USER) shouldBe LaneStep.Continue
                worker.step(USER) shouldBe LaneStep.Sleep(T0 + INTERVAL_MS)
                refreshes.get() shouldBe 1
            }
        }

        test("401 again after the refresh breaks the connection as REVOKED") {
            pullWorkerTest {
                connect()
                repeat(2) { hardcover.failNext(FakeReply(HttpStatusCode.Unauthorized, """{"error":"invalid_token"}""")) }
                worker.step(USER) shouldBe LaneStep.Continue
                worker.step(USER) shouldBe LaneStep.Stop
                connections.connectionFor(USER).shouldBeInstanceOf<StoredConnection.Broken>().reason shouldBe HardcoverBrokenReason.REVOKED
            }
        }

        test("403 insufficient_scope breaks the connection as MISSING_SCOPE") {
            pullWorkerTest {
                connect()
                hardcover.failNext(FakeReply(HttpStatusCode.Forbidden, """{"error":"insufficient_scope","scope":"read:library"}"""))
                worker.step(USER) shouldBe LaneStep.Stop
                connections.connectionFor(USER).shouldBeInstanceOf<StoredConnection.Broken>().reason shouldBe
                    HardcoverBrokenReason.MISSING_SCOPE
            }
        }

        test("no connection, or a broken one, stops the lane without asking Hardcover") {
            pullWorkerTest {
                worker.step(USER) shouldBe LaneStep.Stop
                connect()
                connections.markBroken(USER, HardcoverBrokenReason.REVOKED)
                worker.step(USER) shouldBe LaneStep.Stop
                hardcover.operations shouldBe emptyList()
            }
        }

        test("started, the worker pulls every connected user") {
            pullWorkerTest {
                connect()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    worker.start(scope)
                    eventually(10.seconds) { pulls() shouldBe 1 }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("Sync now without a working connection says so, and pulls nothing") {
            pullWorkerTest {
                worker
                    .syncNow(USER)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.NotConnected>()
                connect()
                connections.markBroken(USER, HardcoverBrokenReason.REVOKED)
                worker
                    .syncNow(USER)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.ConnectionBroken>()
                pushNudges shouldBe emptyList()
                hardcover.operations shouldBe emptyList()
            }
        }

        test("Sync now pulls the whole shelf at once and wakes the push lane") {
            pullWorkerTest {
                connect()
                worker.step(USER)
                at(T0 + 60_000L)
                worker.step(USER) shouldBe LaneStep.Sleep(T0 + INTERVAL_MS)

                worker.syncNow(USER) shouldBe AppResult.Success(Unit)

                pushNudges shouldBe listOf(USER)
                worker.step(USER).shouldBeInstanceOf<LaneStep.Sleep>()
                pulls() shouldBe 2
                lastAfter() shouldBe PULL_EPOCH
            }
        }

        test("Sync now asked while a full pull is finishing still gets its own full pull") {
            pullWorkerTest {
                connect()
                hardcover.seedShelf(1L, HardcoverStatus.READ, null to null)
                // The first pull is a full one; Sync now lands while its last page is in flight.
                hardcover.whileInFlight = {
                    hardcover.whileInFlight = null
                    worker.syncNow(USER) shouldBe AppResult.Success(Unit)
                }
                worker.step(USER).shouldBeInstanceOf<LaneStep.Sleep>()
                pulls() shouldBe 1

                worker.step(USER).shouldBeInstanceOf<LaneStep.Sleep>()
                pulls() shouldBe 2
                lastAfter() shouldBe PULL_EPOCH
            }
        }

        test("a foreground right after a pull asks nothing; one later pulls what changed") {
            pullWorkerTest {
                connect()
                worker.step(USER)

                at(T0 + PULL_STALE_AFTER.inWholeMilliseconds - 1)
                worker.syncIfStale(USER)
                worker.step(USER).shouldBeInstanceOf<LaneStep.Sleep>()
                pulls() shouldBe 1

                at(T0 + PULL_STALE_AFTER.inWholeMilliseconds)
                worker.syncIfStale(USER)
                worker.step(USER).shouldBeInstanceOf<LaneStep.Sleep>()
                pulls() shouldBe 2
            }
        }

        test("a foreground with no connection is quietly nothing") {
            pullWorkerTest {
                worker.syncIfStale(USER)
                hardcover.operations shouldBe emptyList()
            }
        }

        test("a changed match forgets that book's pulled reads and re-pulls the whole shelf") {
            pullWorkerTest {
                connect()
                worker.step(USER)
                store.commitPage(USER, listOf(PulledBook("book-x", listOf(PulledRead(7L, 100L)), null)), "c", 1L, T0)
                at(T0 + 60_000L)

                worker.onMatchChanged(USER, "book-x")

                store.pulledReads(USER) shouldBe emptyList()
                worker.step(USER).shouldBeInstanceOf<LaneStep.Sleep>()
                lastAfter() shouldBe PULL_EPOCH
            }
        }

        test("a book synced again pulls the whole shelf at once") {
            pullWorkerTest {
                connect()
                worker.step(USER)
                store.commitPage(USER, emptyList(), "c", 1L, T0)
                at(T0 + 60_000L)

                worker.fullPullNow(USER)

                worker.step(USER).shouldBeInstanceOf<LaneStep.Sleep>()
                lastAfter() shouldBe PULL_EPOCH
            }
        }

        test("without a working connection a full pull now pulls nothing") {
            pullWorkerTest {
                worker.fullPullNow(USER)
                worker.step(USER) shouldBe LaneStep.Stop
                pulls() shouldBe 0
            }
        }

        test("Sync now is syncing until the full pull it asked for catches up") {
            pullWorkerTest {
                connect()
                worker.syncNow(USER) shouldBe AppResult.Success(Unit)
                activity.isSyncing(USER) shouldBe true
                worker.step(USER) shouldBe LaneStep.Sleep(T0 + INTERVAL_MS)
                activity.isSyncing(USER) shouldBe false
                activity.syncNowFailed(USER) shouldBe false
            }
        }

        test("a Sync now whose pull fails says so, and the next pull that catches up clears it") {
            pullWorkerTest {
                connect()
                worker.syncNow(USER)
                hardcover.failNext(FakeReply(HttpStatusCode.InternalServerError))
                worker.step(USER)
                activity.isSyncing(USER) shouldBe false
                activity.syncNowFailed(USER) shouldBe true
                at(T0 + INTERVAL_MS)
                worker.step(USER)
                activity.syncNowFailed(USER) shouldBe false
            }
        }

        test("a throttled Sync now is still syncing: it is waiting, not failing") {
            pullWorkerTest {
                connect()
                worker.syncNow(USER)
                hardcover.failNext(FakeReply(HttpStatusCode.TooManyRequests, headers = mapOf("Retry-After" to "120")))
                worker.step(USER)
                activity.isSyncing(USER) shouldBe true
                activity.syncNowFailed(USER) shouldBe false
            }
        }

        test("a periodic pull that fails is not a failed Sync now") {
            pullWorkerTest {
                connect()
                hardcover.failNext(FakeReply(HttpStatusCode.InternalServerError))
                worker.step(USER)
                activity.syncNowFailed(USER) shouldBe false
            }
        }
    })
