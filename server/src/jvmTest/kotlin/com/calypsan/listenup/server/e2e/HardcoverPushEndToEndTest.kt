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
import io.kotest.matchers.collections.shouldNotBeEmpty
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.koin.ktor.ext.get as koinGet

/**
 * The whole push path, with only Hardcover faked: a listener's first position, a minute and a half of
 * listening, and a finish go in through the playback RPCs; "Currently Reading", the progress and "Read"
 * come out on a fake Hardcover served over a real socket, through the real matcher, outbox, worker and
 * GraphQL client. The fake replaces a read wholesale on every update ([FakeHardcoverLibrary.ReadUpdates.REPLACE]),
 * the harsher of Hardcover's two possible behaviours, so a push that sent a lone field would lose the
 * read's start date.
 */
class HardcoverPushEndToEndTest :
    FunSpec({

        test("a real start, its progress, and a finish reach Hardcover as Reading, a position, and Read") {
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
                    val playback = authedService<PlaybackService>(session.accessToken.value)
                    val startedAt = System.currentTimeMillis() - 10 * 60_000L
                    val startedOn =
                        Instant
                            .fromEpochMilliseconds(startedAt)
                            .toLocalDateTime(TimeZone.UTC)
                            .date
                            .toString()

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

                    eventually(20.seconds) {
                        val shelf = hardcover.shelfFor(427_578L).shouldNotBeNull()
                        shelf.statusId shouldBe HardcoverStatus.READING
                        shelf.editionId shouldBe 9_001L
                        shelf.reads.single().progressSeconds shouldBe 90L
                    }

                    val finishedAt = startedAt + 5 * 60_000L
                    playback
                        .recordPosition(
                            RecordPositionRequest(
                                bookId = "book-1",
                                positionMs = 90_000L,
                                lastPlayedAt = finishedAt,
                                finished = true,
                                playbackSpeed = 1f,
                                currentChapterId = null,
                                finishedAt = finishedAt,
                            ),
                        ).shouldSucceed()

                    eventually(20.seconds) {
                        val shelf = hardcover.shelfFor(427_578L).shouldNotBeNull()
                        shelf.statusId shouldBe HardcoverStatus.READ
                        shelf.reads.single().finishedAt shouldBe
                            Instant
                                .fromEpochMilliseconds(finishedAt)
                                .toLocalDateTime(TimeZone.UTC)
                                .date
                                .toString()
                    }
                    // One shelf entry, and the read Hardcover opened for it adopted: nothing was duplicated on the way.
                    hardcover.operations.filter { it.startsWith("insert_") } shouldBe listOf("insert_user_book")
                    hardcover.shelfFor(427_578L)!!.reads.size shouldBe 1
                    // Every read update carried the read's whole state, so replacing the read kept its
                    // start date, its position and its edition.
                    val readUpdates = hardcover.requests.filter { it.operation == "update_user_book_read" }
                    readUpdates.shouldNotBeEmpty()
                    readUpdates.forEach { update ->
                        val sent = update.variables.getValue("read").jsonObject
                        (sent["started_at"] ?: JsonNull).toString() shouldBe "\"$startedOn\""
                    }
                    val read =
                        hardcover
                            .shelfFor(427_578L)
                            .shouldNotBeNull()
                            .reads
                            .single()
                    read.startedAt shouldBe startedOn
                    read.progressSeconds shouldBe 90L
                    read.editionId shouldBe 9_001L
                }
            } finally {
                fakeHardcover.stop(0, 0)
            }
        }
    })
