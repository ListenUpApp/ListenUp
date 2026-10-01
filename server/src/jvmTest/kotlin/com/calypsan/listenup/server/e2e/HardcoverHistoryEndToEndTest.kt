package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.api.HardcoverService
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.hardcover.FakeHardcoverLibrary
import com.calypsan.listenup.server.hardcover.HARDCOVER_SCOPES
import com.calypsan.listenup.server.hardcover.HardcoverConnectionStore
import com.calypsan.listenup.server.hardcover.HardcoverMe
import com.calypsan.listenup.server.hardcover.HardcoverOutbox
import com.calypsan.listenup.server.hardcover.HardcoverStatus
import com.calypsan.listenup.server.hardcover.HardcoverTokens
import com.calypsan.listenup.server.hardcover.seedListeningEvent
import com.calypsan.listenup.server.hardcover.seedOwnRead
import com.calypsan.listenup.server.hardcover.seedPulledRead
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.publicAuthService
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.seconds
import org.koin.ktor.ext.get as koinGet

private const val HAIL_MARY = 427_578L
private const val PIRANESI = 312_460L

/** Midday UTC on [date], in epoch ms — far from midnight, so no zone moves the day. */
private fun noon(date: String): Long =
    LocalDate
        .parse(date)
        .atTime(12, 0)
        .toInstant(TimeZone.UTC)
        .toEpochMilliseconds()

/**
 * The history backfill end to end, with only Hardcover faked:
 * - the listener read Project Hail Mary twice in ListenUp before connecting;
 * - they read Piranesi on paper, logged it on Hardcover (pulled into ListenUp), then listened to it once.
 *
 * They connect, are offered two books, and send. Hail Mary arrives as one Read entry with both reads,
 * each dated as listened. Piranesi is already Read on Hardcover, so nothing is added, and the pulled read
 * never goes back. The send ends Done. A second send changes nothing.
 */
class HardcoverHistoryEndToEndTest :
    FunSpec({

        test("earlier listening reaches Hardcover once, dated, filling in and never duplicating") {
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
            val piranesiOnPaper =
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
                    app.koinGet<ListenUpDatabase>().apply {
                        seedTestLibraryAndFolder()
                        seedTestBook("book-1", asin = "B08G9RZBTT")
                        seedTestBook("book-2", asin = "B08F2BYBZ4")
                        seedListeningEvent(userId, "book-1", "e1", startedAt = noon("2025-03-01"))
                        seedOwnRead(userId, "book-1", "r1", finishedAt = noon("2025-03-10"))
                        seedListeningEvent(userId, "book-1", "e2", startedAt = noon("2025-06-01"))
                        seedOwnRead(userId, "book-1", "r2", finishedAt = noon("2025-06-05"))
                        seedPulledRead(userId, "book-2", hcReadId = piranesiOnPaper, finishedAt = noon("2025-01-20"))
                        seedListeningEvent(userId, "book-2", "e3", startedAt = noon("2025-08-01"))
                        seedOwnRead(userId, "book-2", "r3", finishedAt = noon("2025-08-09"))
                    }
                    val store = app.koinGet<HardcoverConnectionStore>()
                    store.save(userId, HardcoverMe(42, "simon"), HardcoverTokens("hc_at_e2e", "hc_rt_e2e", 604_800, HARDCOVER_SCOPES))

                    suspend fun history() = store.connectionState(userId).shouldBeInstanceOf<HardcoverConnection.Connected>().history
                    history() shouldBe HardcoverHistory.Offer(bookCount = 2)

                    val service = authedService<HardcoverService>(session.accessToken.value)
                    service.sendHistory().shouldSucceed()

                    // Every Hardcover call waits on the shared rate limiter (one a second), so this takes a while.
                    eventually(60.seconds) { history() shouldBe HardcoverHistory.Done(sentBooks = 2, needsMatchBooks = 0) }

                    val hailMary = hardcover.shelfFor(HAIL_MARY).shouldNotBeNull()
                    hailMary.statusId shouldBe HardcoverStatus.READ
                    hailMary.editionId shouldBe 9_001L
                    hailMary.reads.map { it.startedAt to it.finishedAt } shouldBe
                        listOf("2025-03-01" to "2025-03-10", "2025-06-01" to "2025-06-05")
                    hardcover
                        .shelfFor(PIRANESI)
                        .shouldNotBeNull()
                        .reads
                        .map { it.startedAt to it.finishedAt } shouldBe
                        listOf("2025-01-03" to "2025-01-20")
                    val writes = hardcover.operations.filter { it.startsWith("insert_") || it.startsWith("update_") }
                    writes.filter { it.startsWith("insert_") } shouldBe listOf("insert_user_book", "insert_user_book_read")

                    service.sendHistory().shouldSucceed()
                    app.koinGet<HardcoverOutbox>().pendingFor(userId) shouldBe emptyList()
                    hardcover.operations.filter { it.startsWith("insert_") || it.startsWith("update_") } shouldBe writes
                    history() shouldBe HardcoverHistory.Done(sentBooks = 2, needsMatchBooks = 0)
                }
            } finally {
                fakeHardcover.stop(0, 0)
            }
        }
    })
