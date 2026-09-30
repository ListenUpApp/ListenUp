package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val USER = "u1"
private const val BOOK = "book-1"
private const val T0 = 1_779_451_200_000L
private val HAIL_MARY_IDENTITY = BookIdentity(asin = "B08G9RZBTT", title = "Project Hail Mary", primaryAuthor = "Andy Weir")

private class WorkerRig(
    val sql: ListenUpDatabase,
    refreshStatus: HttpStatusCode,
) {
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
    private val tokens = HardcoverTokenProvider(oauth, connections, linker, clock)
    val links = HardcoverBookLinkStore(sql, clock)
    val outbox = HardcoverOutbox(sql, clock)
    var identity: BookIdentity? = HAIL_MARY_IDENTITY
    val worker =
        HardcoverPushWorker(
            outbox = outbox,
            links = links,
            matcher = HardcoverBookMatcher(graphQl, NoWaitRateLimiter()),
            executor = HardcoverPushExecutor(HardcoverUserBooks(graphQl), links, outbox, sql, clock),
            tokens = tokens,
            connections = connections,
            linker = linker,
            identities = HardcoverBookIdentities { identity },
            clock = clock,
        )

    init {
        sql.seedTestUser(USER)
        sql.seedTestLibraryAndFolder()
        sql.seedTestBook(BOOK)
        hardcover.addEdition(
            FakeHardcoverLibrary.Edition(9_001L, 427_578L, "Project Hail Mary", listOf("Andy Weir"), asin = "B08G9RZBTT", readingFormatId = 2),
        )
    }

    suspend fun connect() = connections.save(USER, HardcoverMe(42, "reader"), HardcoverTokens("hc_at_1", "hc_rt_1", 604_800, HARDCOVER_SCOPES))

    suspend fun linkBook() = links.recordAutomaticMatch(USER, BOOK, HardcoverMatch(427_578L, 9_001L, HardcoverMatchMethod.ASIN))

    suspend fun queueStart() = outbox.enqueueStart(USER, BOOK, listenThrough = T0, startedAt = T0, isReread = false)

    /** Steps the lane until it sleeps or stops, as the coroutine lane would between wake-ups. */
    suspend fun drain(): LaneStep {
        var step: LaneStep
        do {
            step = worker.step(USER)
        } while (step == LaneStep.Continue)
        return step
    }
}

private fun workerTest(
    refreshStatus: HttpStatusCode = HttpStatusCode.OK,
    block: suspend WorkerRig.() -> Unit,
) = withSqlDatabase { runTest { WorkerRig(sql, refreshStatus).block() } }

/** Spec B2's worker: one sequential lane per user, only while connected, and a policy for every failure. */
class HardcoverPushWorkerTest :
    FunSpec({

        test("a queued START matches its book lazily, pushes, completes the row and marks the connection synced") {
            workerTest {
                connect()
                queueStart()
                drain() shouldBe LaneStep.Stop

                outbox.pendingFor(USER).shouldBeEmpty()
                links.linkFor(USER, BOOK)!!.method shouldBe HardcoverMatchMethod.ASIN
                hardcover.shelfFor(427_578L)!!.statusId shouldBe HardcoverStatus.READING
                hardcover.operations shouldBe listOf("edition_by_asin", "user_books", "insert_user_book", "insert_user_book_read")
                connections.pushHealth(USER) shouldBe HardcoverPushHealth(lastSyncedAt = T0, pushError = null)
            }
        }

        test("a book with no confident match is NEEDS_MATCH, and its row waits, never guessed") {
            workerTest {
                connect()
                identity = BookIdentity(title = "A Book Hardcover Has Never Heard Of")
                queueStart()
                drain() shouldBe LaneStep.Stop
                links.linkFor(USER, BOOK)!!.isLinked shouldBe false
                outbox.pendingFor(USER).size shouldBe 1
                hardcover.shelfFor(427_578L) shouldBe null
            }
        }

        test("a book gone from the library keeps its row, retried slowly, never dropped") {
            workerTest {
                connect()
                identity = null
                queueStart()
                worker.step(USER) shouldBe LaneStep.Continue
                outbox.pendingFor(USER).single().attempts shouldBe 1
                outbox.nextWakeAt(USER) shouldBe T0 + 6.hours.inWholeMilliseconds
                hardcover.operations.shouldBeEmpty()
            }
        }

        test("429 honours Retry-After, and the whole lane pauses until then") {
            workerTest {
                connect()
                linkBook()
                queueStart()
                hardcover.failNext(FakeReply(HttpStatusCode.TooManyRequests, headers = mapOf("Retry-After" to "120")))

                worker.step(USER) shouldBe LaneStep.Sleep(T0 + 120_000L)
                worker.step(USER) shouldBe LaneStep.Sleep(T0 + 120_000L)
                hardcover.operations.size shouldBe 1
                outbox.pendingFor(USER).single().attempts shouldBe 1

                clock.instant = Instant.fromEpochMilliseconds(T0 + 120_000L)
                drain() shouldBe LaneStep.Stop
                outbox.pendingFor(USER).shouldBeEmpty()
            }
        }

        test("503 without a hint backs off 30 seconds") {
            workerTest {
                connect()
                linkBook()
                queueStart()
                hardcover.failNext(FakeReply(HttpStatusCode.ServiceUnavailable))
                worker.step(USER) shouldBe LaneStep.Sleep(T0 + 30.seconds.inWholeMilliseconds)
            }
        }

        test("408 keeps the row and retries it after a minute; other books are not held up") {
            workerTest {
                connect()
                linkBook()
                queueStart()
                hardcover.failNext(FakeReply(HttpStatusCode.RequestTimeout))

                worker.step(USER) shouldBe LaneStep.Continue
                val row = outbox.pendingFor(USER).single()
                row.attempts shouldBe 1
                outbox.nextWakeAt(USER) shouldBe T0 + 60_000L
                connections.pushHealth(USER)!!.pushError shouldBe null
            }
        }

        test("500 past the retry cap keeps the row, retries every 6 hours, and shows the error on the connection") {
            workerTest {
                connect()
                linkBook()
                queueStart()
                val row = outbox.pendingFor(USER).single()
                outbox.reschedule(row.id, attempts = PUSH_MAX_ATTEMPTS - 1, nextAttemptAt = T0, lastError = "earlier")
                hardcover.failNext(FakeReply(HttpStatusCode.InternalServerError))

                worker.step(USER) shouldBe LaneStep.Continue

                outbox.pendingFor(USER).single().attempts shouldBe PUSH_MAX_ATTEMPTS
                outbox.nextWakeAt(USER) shouldBe T0 + 6.hours.inWholeMilliseconds
                connections.pushHealth(USER)!!.pushError shouldBe "userBookFor 500"
            }
        }

        test("401 refreshes once and the retried row lands") {
            workerTest {
                connect()
                linkBook()
                queueStart()
                hardcover.failNext(FakeReply(HttpStatusCode.Unauthorized, """{"error":"invalid_token"}"""))

                drain() shouldBe LaneStep.Stop
                refreshes.get() shouldBe 1
                outbox.pendingFor(USER).shouldBeEmpty()
            }
        }

        test("401 again after the refresh breaks the connection as REVOKED, and keeps the row") {
            workerTest {
                connect()
                linkBook()
                queueStart()
                repeat(2) { hardcover.failNext(FakeReply(HttpStatusCode.Unauthorized, """{"error":"invalid_token"}""")) }

                drain() shouldBe LaneStep.Stop
                connections.connectionFor(USER).shouldBeInstanceOf<StoredConnection.Broken>().reason shouldBe HardcoverBrokenReason.REVOKED
                outbox.pendingFor(USER).size shouldBe 1
            }
        }

        test("a 401 whose refresh can't reach Hardcover waits, and is not counted as the refresh") {
            workerTest(refreshStatus = HttpStatusCode.ServiceUnavailable) {
                connect()
                linkBook()
                queueStart()
                hardcover.failNext(FakeReply(HttpStatusCode.Unauthorized, """{"error":"invalid_token"}"""))

                worker.step(USER) shouldBe LaneStep.Sleep(T0 + 5 * 60_000L)
                hardcover.failNext(FakeReply(HttpStatusCode.Unauthorized, """{"error":"invalid_token"}"""))
                worker.step(USER) shouldBe LaneStep.Sleep(T0 + 5 * 60_000L)

                connections.connectionFor(USER).shouldBeInstanceOf<StoredConnection.Healthy>()
                refreshes.get() shouldBe 2
                outbox.pendingFor(USER).size shouldBe 1
            }
        }

        test("403 insufficient_scope breaks the connection as MISSING_SCOPE") {
            workerTest {
                connect()
                linkBook()
                queueStart()
                hardcover.failNext(FakeReply(HttpStatusCode.Forbidden, """{"error":"insufficient_scope","scope":"write:library"}"""))

                worker.step(USER) shouldBe LaneStep.Stop
                connections.connectionFor(USER).shouldBeInstanceOf<StoredConnection.Broken>().reason shouldBe HardcoverBrokenReason.MISSING_SCOPE
                outbox.pendingFor(USER).size shouldBe 1
            }
        }

        test("a broken connection stops the lane without asking Hardcover anything") {
            workerTest {
                connect()
                connections.markBroken(USER, HardcoverBrokenReason.REVOKED)
                queueStart()
                worker.step(USER) shouldBe LaneStep.Stop
                hardcover.operations.shouldBeEmpty()
            }
        }

        test("a nudge starts the user's lane, which drains the outbox and retires") {
            workerTest {
                connect()
                queueStart()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    worker.start(scope)
                    worker.nudge(USER)
                    eventually(10.seconds) { outbox.pendingFor(USER).shouldBeEmpty() }
                } finally {
                    scope.cancel()
                }
            }
        }
    })
