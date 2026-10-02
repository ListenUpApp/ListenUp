package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.api.HardcoverService
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.hardcover.FakeHardcoverLibrary
import com.calypsan.listenup.server.hardcover.HARDCOVER_SCOPES
import com.calypsan.listenup.server.hardcover.HardcoverConnectionStore
import com.calypsan.listenup.server.hardcover.HardcoverMe
import com.calypsan.listenup.server.hardcover.HardcoverOutbox
import com.calypsan.listenup.server.hardcover.HardcoverPullStore
import com.calypsan.listenup.server.hardcover.HardcoverPushHook
import com.calypsan.listenup.server.hardcover.HardcoverShelfEntryStore
import com.calypsan.listenup.server.hardcover.HardcoverStatus
import com.calypsan.listenup.server.hardcover.HardcoverTokens
import com.calypsan.listenup.server.hardcover.seedListeningEvent
import com.calypsan.listenup.server.hardcover.seedOwnRead
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.publicAuthService
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.koin.ktor.ext.get as koinGet

private const val HAIL_MARY = 427_578L
private const val PIRANESI = 312_460L
private const val DAY = 86_400_000L

/** [epochMs] as the date Hardcover is sent for it, in UTC — the test listener's home zone. */
private fun utcDate(epochMs: Long): String =
    Instant
        .fromEpochMilliseconds(epochMs)
        .toLocalDateTime(TimeZone.UTC)
        .date
        .toString()

/**
 * Keeping a book off Hardcover end to end, with only Hardcover faked:
 * - Project Hail Mary waits on the listener's Hardcover Want to Read, so the pull puts it on their To Read shelf;
 * - Piranesi was read on paper and logged on Hardcover, so the pull mirrors that read into Readers.
 *
 * They keep both off: the To Read entry and the mirrored read leave ListenUp, and nothing on Hardcover changes.
 * They finish Hail Mary while it is kept off: nothing is queued or sent, and a full pull brings nothing back.
 * They sync both again: exactly one Read read of Hail Mary arrives, dated as listened, and the next pull brings
 * Piranesi's read back.
 */
class HardcoverKeepOffEndToEndTest :
    FunSpec({

        test("a book kept off sends nothing and brings nothing in; synced again, it catches up both ways") {
            val hardcover = FakeHardcoverLibrary()
            hardcover.addEdition(
                FakeHardcoverLibrary.Edition(
                    id = 9_001L,
                    bookId = HAIL_MARY,
                    title = "Project Hail Mary",
                    authors = listOf("Andy Weir"),
                    asin = "B08G9RZBTT",
                    readingFormatId = 2,
                    defaultAudioEditionId = 9_001L,
                ),
            )
            hardcover.addEdition(
                FakeHardcoverLibrary.Edition(
                    id = 9_002L,
                    bookId = PIRANESI,
                    title = "Piranesi",
                    authors = listOf("Susanna Clarke"),
                    asin = "B08F2BYBZ4",
                    readingFormatId = 2,
                    defaultAudioEditionId = 9_002L,
                ),
            )
            hardcover.seedShelf(HAIL_MARY, HardcoverStatus.WANT_TO_READ, editionId = 9_001L)
            val paperRead =
                hardcover
                    .seedShelf(PIRANESI, HardcoverStatus.READ, "2025-01-03" to "2025-01-20", editionId = 9_002L)
                    .reads
                    .single()
                    .id
            val fakeHardcover =
                embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                    routing {
                        post("/v1/graphql") {
                            val reply = hardcover.handle(call.receiveText())
                            reply.headers.forEach { (name, value) -> call.response.header(name, value) }
                            call.respondText(reply.body, ContentType.Application.Json, reply.status)
                        }
                    }
                }.start(wait = false)
            try {
                val port = runBlocking { fakeHardcover.engine.resolvedConnectors() }.first().port
                testApplication {
                    useIsolatedTestConfig(
                        rescanOnStartup = false,
                        hardcoverClientId = "listenup-test-client",
                        hardcoverApiBaseUrl = "http://127.0.0.1:$port",
                    )
                    lateinit var app: Application
                    application {
                        module()
                        app = this
                    }
                    startApplication()

                    val session = publicAuthService().setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                    val userId = session.user.id.value
                    val sql = app.koinGet<ListenUpDatabase>()
                    sql.seedTestLibraryAndFolder()
                    sql.seedTestBook("book-1", asin = "B08G9RZBTT")
                    sql.seedTestBook("book-2", asin = "B08F2BYBZ4")
                    app.koinGet<HardcoverConnectionStore>().save(
                        userId,
                        HardcoverMe(42, "simon"),
                        HardcoverTokens("hc_at_e2e", "hc_rt_e2e", 604_800, HARDCOVER_SCOPES),
                    )
                    val service = authedService<HardcoverService>(session.accessToken.value)
                    val pulls = app.koinGet<HardcoverPullStore>()
                    val shelfEntries = app.koinGet<HardcoverShelfEntryStore>()

                    suspend fun fullPullFromNow() {
                        val from = System.currentTimeMillis()
                        service.syncNow().shouldSucceed()
                        eventually(20.seconds) { (pulls.pullState(userId)!!.lastFullPullAt ?: 0L) shouldBeGreaterThanOrEqualTo from }
                    }

                    fun hardcoverWrites() =
                        hardcover.operations.filter {
                            it.startsWith("insert_") || it.startsWith("update_") || it.startsWith("delete_")
                        }

                    fullPullFromNow()
                    pulls.pulledReads(userId).map { it.hcReadId } shouldBe listOf(paperRead)
                    shelfEntries.recordFor(userId, "book-1").shouldNotBeNull()

                    service.setBookSynced(BookId("book-1"), synced = false).shouldSucceed()
                    service.setBookSynced(BookId("book-2"), synced = false).shouldSucceed()

                    pulls.pulledReads(userId) shouldBe emptyList()
                    shelfEntries.recordFor(userId, "book-1") shouldBe null
                    service.bookMatch(BookId("book-1")).shouldSucceed() shouldBe HardcoverBookMatch.KeptOff
                    service.keptOffBooks().shouldSucceed() shouldBe listOf(BookId("book-1"), BookId("book-2"))

                    // Hail Mary is finished while it is kept off.
                    val finishedAt = System.currentTimeMillis()
                    val startedAt = finishedAt - 3 * DAY
                    sql.seedListeningEvent(userId, "book-1", "e1", startedAt = startedAt)
                    sql.seedOwnRead(userId, "book-1", "r1", finishedAt = finishedAt)
                    app.koinGet<HardcoverPushHook>().onReadAppended(userId, "book-1", finishedAt)
                    app.koinGet<HardcoverOutbox>().pendingFor(userId) shouldBe emptyList()
                    fullPullFromNow()
                    pulls.pulledReads(userId) shouldBe emptyList()
                    hardcoverWrites() shouldBe emptyList()

                    service.setBookSynced(BookId("book-1"), synced = true).shouldSucceed()
                    service.setBookSynced(BookId("book-2"), synced = true).shouldSucceed()

                    // Every Hardcover call waits on the shared rate limiter (one a second), so this takes a while.
                    eventually(60.seconds) {
                        val hailMary = hardcover.shelfFor(HAIL_MARY).shouldNotBeNull()
                        hailMary.statusId shouldBe HardcoverStatus.READ
                        hailMary.reads.map { it.startedAt to it.finishedAt } shouldBe listOf(utcDate(startedAt) to utcDate(finishedAt))
                    }
                    eventually(30.seconds) { pulls.pulledReads(userId).map { it.hcReadId } shouldBe listOf(paperRead) }
                    service.keptOffBooks().shouldSucceed() shouldBe emptyList()
                    hardcover.shelfFor(PIRANESI)!!.reads.map { it.startedAt to it.finishedAt } shouldBe listOf("2025-01-03" to "2025-01-20")
                }
            } finally {
                fakeHardcover.stop(0, 0)
            }
        }
    })
