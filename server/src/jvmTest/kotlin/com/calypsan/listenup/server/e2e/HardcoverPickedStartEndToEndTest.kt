package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.hardcover.FakeHardcoverLibrary
import com.calypsan.listenup.server.hardcover.HARDCOVER_SCOPES
import com.calypsan.listenup.server.hardcover.HardcoverConnectionStore
import com.calypsan.listenup.server.hardcover.HardcoverMe
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.koin.ktor.ext.get as koinGet

private const val HC_BOOK = 427_578L

/** The days the reader gives, at noon UTC so the user's home zone (UTC by default) can't move them. */
private val SEP_1 = Instant.parse("2026-09-01T12:00:00Z").toEpochMilliseconds()
private val SEP_20 = Instant.parse("2026-09-20T12:00:00Z").toEpochMilliseconds()
private val SEP_30 = Instant.parse("2026-09-30T12:00:00Z").toEpochMilliseconds()

/** A connected reader, a matched book, and the fake Hardcover behind it. */
private class PickedStartRig(
    val hardcover: FakeHardcoverLibrary,
    val playback: PlaybackService,
)

/**
 * Boots the real server against a fake Hardcover, connects a reader, seeds one book Hardcover knows by
 * ASIN, and hands [block] the reader's playback service.
 */
private fun withConnectedReader(block: suspend ApplicationTestBuilder.(PickedStartRig) -> Unit) {
    // REPLACE: an update that sent the finish alone would wipe the start, so the start must be sent.
    val hardcover = FakeHardcoverLibrary(FakeHardcoverLibrary.ReadUpdates.REPLACE)
    hardcover.addEdition(
        FakeHardcoverLibrary.Edition(
            id = 9_001L,
            bookId = HC_BOOK,
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
            app.koinGet<ListenUpDatabase>().apply {
                seedTestLibraryAndFolder()
                seedTestBook("book-1", asin = "B08G9RZBTT")
            }
            app.koinGet<HardcoverConnectionStore>().save(
                session.user.id.value,
                HardcoverMe(42, "simon"),
                HardcoverTokens("hc_at_e2e", "hc_rt_e2e", 604_800, HARDCOVER_SCOPES),
            )
            block(PickedStartRig(hardcover, authedService<PlaybackService>(session.accessToken.value)))
        }
    } finally {
        fakeHardcover.stop(0, 0)
    }
}

private fun markFinished(
    startedAt: Long?,
    finishedAt: Long,
) = RecordPositionRequest(
    bookId = "book-1",
    positionMs = 240_000L,
    lastPlayedAt = finishedAt,
    finished = true,
    playbackSpeed = 1f,
    currentChapterId = null,
    finishedAt = finishedAt,
    startedAt = startedAt,
)

/**
 * The start day a reader picks in "Mark as finished" is the start of the read Hardcover holds — end to
 * end, with only Hardcover faked: over RPC, through the position write, the stats cascade, the outbox
 * and the push executor.
 */
class HardcoverPickedStartEndToEndTest :
    FunSpec({

        test("a picked start of Sep 1 and finish of Sep 30 is the read Hardcover holds, over the start ListenUp saw") {
            withConnectedReader { rig ->
                // Listening in ListenUp began Sep 20 and crossed the real-listen line: Hardcover has a read from then.
                rig.playback
                    .recordPosition(
                        RecordPositionRequest(
                            bookId = "book-1",
                            positionMs = 0L,
                            lastPlayedAt = SEP_20,
                            finished = false,
                            playbackSpeed = 1f,
                            currentChapterId = null,
                        ),
                    ).shouldSucceed()
                rig.playback
                    .recordListeningEvent(
                        RecordListeningEventRequest(
                            id = "e1",
                            bookId = "book-1",
                            startPositionMs = 0L,
                            endPositionMs = 90_000L,
                            startedAt = SEP_20,
                            endedAt = SEP_20 + 90_000L,
                            playbackSpeed = 1f,
                            tz = "UTC",
                            deviceLabel = null,
                        ),
                    ).shouldSucceed()
                eventually(20.seconds) {
                    rig.hardcover
                        .shelfFor(HC_BOOK)
                        .shouldNotBeNull()
                        .reads
                        .single()
                        .startedAt shouldBe "2026-09-20"
                }

                // The reader then says they really started on Sep 1, and finished on Sep 30.
                rig.playback.recordPosition(markFinished(startedAt = SEP_1, finishedAt = SEP_30)).shouldSucceed()

                eventually(20.seconds) {
                    rig.hardcover
                        .shelfFor(HC_BOOK)
                        .shouldNotBeNull()
                        .statusId shouldBe HardcoverStatus.READ
                }
                val read =
                    rig.hardcover
                        .shelfFor(HC_BOOK)
                        .shouldNotBeNull()
                        .reads
                        .single()
                read.startedAt shouldBe "2026-09-01"
                read.finishedAt shouldBe "2026-09-30"
            }
        }

        test("a book never played in ListenUp still reaches Hardcover with the picked start") {
            withConnectedReader { rig ->
                rig.playback.recordPosition(markFinished(startedAt = SEP_1, finishedAt = SEP_30)).shouldSucceed()

                eventually(20.seconds) {
                    rig.hardcover
                        .shelfFor(HC_BOOK)
                        .shouldNotBeNull()
                        .statusId shouldBe HardcoverStatus.READ
                }
                val read =
                    rig.hardcover
                        .shelfFor(HC_BOOK)
                        .shouldNotBeNull()
                        .reads
                        .single()
                read.startedAt shouldBe "2026-09-01"
                read.finishedAt shouldBe "2026-09-30"
            }
        }

        test("with no picked start, a finish keeps the start ListenUp saw") {
            withConnectedReader { rig ->
                rig.playback
                    .recordPosition(
                        RecordPositionRequest(
                            bookId = "book-1",
                            positionMs = 0L,
                            lastPlayedAt = SEP_20,
                            finished = false,
                            playbackSpeed = 1f,
                            currentChapterId = null,
                        ),
                    ).shouldSucceed()
                rig.playback
                    .recordListeningEvent(
                        RecordListeningEventRequest(
                            id = "e1",
                            bookId = "book-1",
                            startPositionMs = 0L,
                            endPositionMs = 90_000L,
                            startedAt = SEP_20,
                            endedAt = SEP_20 + 90_000L,
                            playbackSpeed = 1f,
                            tz = "UTC",
                            deviceLabel = null,
                        ),
                    ).shouldSucceed()

                rig.playback.recordPosition(markFinished(startedAt = null, finishedAt = SEP_30)).shouldSucceed()

                eventually(20.seconds) {
                    rig.hardcover
                        .shelfFor(HC_BOOK)
                        .shouldNotBeNull()
                        .statusId shouldBe HardcoverStatus.READ
                }
                val read =
                    rig.hardcover
                        .shelfFor(HC_BOOK)
                        .shouldNotBeNull()
                        .reads
                        .single()
                read.startedAt shouldBe "2026-09-20"
                read.finishedAt shouldBe "2026-09-30"
            }
        }
    })
