package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.api.HardcoverService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.hardcover.FakeHardcoverLibrary
import com.calypsan.listenup.server.hardcover.HARDCOVER_SCOPES
import com.calypsan.listenup.server.hardcover.HardcoverConnectionStore
import com.calypsan.listenup.server.hardcover.HardcoverMe
import com.calypsan.listenup.server.hardcover.HardcoverOutbox
import com.calypsan.listenup.server.hardcover.HardcoverStatus
import com.calypsan.listenup.server.hardcover.HardcoverTokens
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.publicAuthService
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
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
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.koin.ktor.ext.get as koinGet

private fun dayOf(epochMs: Long): String =
    Instant
        .fromEpochMilliseconds(epochMs)
        .toLocalDateTime(TimeZone.UTC)
        .date
        .toString()

/**
 * Only when I finish, end to end, with only Hardcover faked: the listener chooses it over RPC, listens
 * past the real-listen line, and finishes. Hardcover sees nothing until the finish, and then exactly one
 * shelf entry with one read — Read, dated from when they started to when they finished — and never a
 * position. The fake replaces a read wholesale on every update ([FakeHardcoverLibrary.ReadUpdates.REPLACE]),
 * so a push that sent a lone field would lose the start date.
 */
class HardcoverFinishedOnlyEndToEndTest :
    FunSpec({

        test("Only when I finish: a full listen-through reaches Hardcover as one Read read with its real dates, and no progress") {
            val hardcover = FakeHardcoverLibrary(FakeHardcoverLibrary.ReadUpdates.REPLACE)
            hardcover.addEdition(
                FakeHardcoverLibrary.Edition(
                    id = 9_001L,
                    bookId = 427_578L,
                    title = "Project Hail Mary",
                    authors = listOf("Andy Weir"),
                    asin = "B08G9RZBTT",
                    readingFormatId = 2,
                    defaultAudioEditionId = 9_001L,
                ),
            )
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
                    }
                    app.koinGet<HardcoverConnectionStore>().save(
                        userId,
                        HardcoverMe(42, "simon"),
                        HardcoverTokens("hc_at_e2e", "hc_rt_e2e", 604_800, HARDCOVER_SCOPES),
                    )
                    authedService<HardcoverService>(session.accessToken.value)
                        .setShareMode(HardcoverShareMode.FINISHED_ONLY)
                        .shouldSucceed()

                    val playback = authedService<PlaybackService>(session.accessToken.value)
                    val startedAt = System.currentTimeMillis() - 10 * 60_000L
                    playback
                        .recordPosition(
                            RecordPositionRequest(
                                bookId = "book-1",
                                positionMs = 0L,
                                lastPlayedAt = startedAt,
                                finished = false,
                                playbackSpeed = 1f,
                                currentChapterId = null,
                            ),
                        ).shouldSucceed()
                    playback
                        .recordListeningEvent(
                            RecordListeningEventRequest(
                                id = "e1",
                                bookId = "book-1",
                                startPositionMs = 0L,
                                endPositionMs = 90_000L,
                                startedAt = startedAt,
                                endedAt = startedAt + 90_000L,
                                playbackSpeed = 1f,
                                tz = "UTC",
                                deviceLabel = null,
                            ),
                        ).shouldSucceed()
                    // A second sitting, after the first crossed the real-listen line: As I listen would queue
                    // its position here. Only when I finish queues nothing, and nothing has reached Hardcover.
                    playback
                        .recordListeningEvent(
                            RecordListeningEventRequest(
                                id = "e2",
                                bookId = "book-1",
                                startPositionMs = 90_000L,
                                endPositionMs = 240_000L,
                                startedAt = startedAt + 90_000L,
                                endedAt = startedAt + 240_000L,
                                playbackSpeed = 1f,
                                tz = "UTC",
                                deviceLabel = null,
                            ),
                        ).shouldSucceed()
                    // A row stays queued until it is sent, so "nothing queued and nothing sent" holds with no race.
                    app.koinGet<HardcoverOutbox>().pendingFor(userId).shouldBeEmpty()
                    hardcover.operations.shouldBeEmpty()

                    val finishedAt = startedAt + 5 * 60_000L
                    playback
                        .recordPosition(
                            RecordPositionRequest(
                                bookId = "book-1",
                                positionMs = 240_000L,
                                lastPlayedAt = finishedAt,
                                finished = true,
                                playbackSpeed = 1f,
                                currentChapterId = null,
                                finishedAt = finishedAt,
                            ),
                        ).shouldSucceed()

                    eventually(20.seconds) {
                        hardcover.shelfFor(427_578L).shouldNotBeNull().statusId shouldBe HardcoverStatus.READ
                    }
                    val shelf = hardcover.shelfFor(427_578L).shouldNotBeNull()
                    shelf.editionId shouldBe 9_001L
                    val read = shelf.reads.single()
                    read.startedAt shouldBe dayOf(startedAt)
                    read.finishedAt shouldBe dayOf(finishedAt)
                    read.progressSeconds.shouldBeNull()
                    hardcover.operations.filter { it.startsWith("insert_") } shouldBe listOf("insert_user_book")
                    // Nothing ListenUp sent ever carried a position.
                    hardcover.requests
                        .filter { it.operation == "update_user_book_read" }
                        .forEach { update ->
                            update.variables
                                .getValue("read")
                                .jsonObject
                                .containsKey("progress_seconds") shouldBe false
                        }
                }
            } finally {
                fakeHardcover.stop(0, 0)
            }
        }
    })
