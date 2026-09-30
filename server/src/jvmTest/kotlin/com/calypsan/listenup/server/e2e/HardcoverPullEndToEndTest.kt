package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.api.HardcoverService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.hardcover.FakeHardcoverLibrary
import com.calypsan.listenup.server.hardcover.HARDCOVER_SCOPES
import com.calypsan.listenup.server.hardcover.HardcoverConnectionStore
import com.calypsan.listenup.server.hardcover.HardcoverMe
import com.calypsan.listenup.server.hardcover.HardcoverPullStore
import com.calypsan.listenup.server.hardcover.HardcoverStatus
import com.calypsan.listenup.server.hardcover.HardcoverTokens
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.deriveUserStats
import com.calypsan.listenup.server.services.homeTimeZone
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
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.koin.ktor.ext.get as koinGet

private const val PAPERBACK_HC_BOOK = 1_000L
private const val AUDIO_HC_BOOK = 427_578L

/**
 * Spec B3 end to end, with only Hardcover faked (over a real socket). The user has two books:
 * - *11/22/63*, read in paperback in 2017 and logged on Hardcover, never played in ListenUp. It appears
 *   as a Hardcover read of the library's audiobook (reverse-matched by ASIN). It is excluded from books
 *   finished, posts no FINISHED_BOOK, and is carried apart on the Readers wire for its badge.
 * - *Project Hail Mary*, listened to and finished in ListenUp, so pushed to Hardcover as Read. "Sync now"
 *   pulls the whole shelf, and that read is not pulled back.
 */
class HardcoverPullEndToEndTest :
    FunSpec({

        test("a Hardcover-only read arrives badged and uncounted; ListenUp's own pushed read is not pulled back") {
            val hardcover = FakeHardcoverLibrary()
            hardcover.addEdition(
                FakeHardcoverLibrary.Edition(
                    id = 1_001L,
                    bookId = PAPERBACK_HC_BOOK,
                    title = "11/22/63",
                    authors = listOf("Stephen King"),
                    asin = "B005UR3VFO",
                    readingFormatId = 2,
                    defaultAudioEditionId = 1_001L,
                ),
            )
            hardcover.addEdition(
                FakeHardcoverLibrary.Edition(
                    id = 9_001L,
                    bookId = AUDIO_HC_BOOK,
                    title = "Project Hail Mary",
                    authors = listOf("Andy Weir"),
                    asin = "B08G9RZBTT",
                    readingFormatId = 2,
                    defaultAudioEditionId = 9_001L,
                ),
            )
            hardcover.seedShelf(PAPERBACK_HC_BOOK, HardcoverStatus.READ, "2017-01-02" to "2017-03-01", editionId = 1_001L)

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
                    sql.seedTestBook("book-paperback", asin = "B005UR3VFO")
                    sql.seedTestBook("book-audio", asin = "B08G9RZBTT")
                    app.koinGet<HardcoverConnectionStore>().save(
                        userId,
                        HardcoverMe(42, "simon"),
                        HardcoverTokens("hc_at_e2e", "hc_rt_e2e", 604_800, HARDCOVER_SCOPES),
                    )

                    // Listen to and finish Project Hail Mary in ListenUp; the push takes it to Hardcover as Read.
                    val playback = authedService<PlaybackService>(session.accessToken.value)
                    val startedAt = System.currentTimeMillis() - 10 * 60_000L
                    playback
                        .recordPosition(
                            RecordPositionRequest(
                                bookId = "book-audio",
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
                                bookId = "book-audio",
                                startPositionMs = 0L,
                                endPositionMs = 90_000L,
                                startedAt = startedAt,
                                endedAt = startedAt + 90_000L,
                                playbackSpeed = 1f,
                                tz = "UTC",
                                deviceLabel = null,
                            ),
                        ).shouldSucceed()
                    val finishedAt = startedAt + 5 * 60_000L
                    playback
                        .recordPosition(
                            RecordPositionRequest(
                                bookId = "book-audio",
                                positionMs = 90_000L,
                                lastPlayedAt = finishedAt,
                                finished = true,
                                playbackSpeed = 1f,
                                currentChapterId = null,
                                finishedAt = finishedAt,
                            ),
                        ).shouldSucceed()
                    eventually(20.seconds) {
                        hardcover
                            .shelfFor(AUDIO_HC_BOOK)
                            .shouldNotBeNull()
                            .reads
                            .single()
                            .finishedAt
                            .shouldNotBeNull()
                    }

                    // Sync now: a full pull of the shelf.
                    authedService<HardcoverService>(session.accessToken.value).syncNow().shouldSucceed()

                    val store = app.koinGet<HardcoverPullStore>()
                    eventually(20.seconds) { store.pulledReads(userId).map { it.bookId } shouldBe listOf("book-paperback") }
                    val zone = sql.homeTimeZone(userId)
                    Instant
                        .fromEpochMilliseconds(store.pulledReads(userId).single().finishedAt)
                        .toLocalDateTime(zone)
                        .date
                        .toString() shouldBe "2017-03-01"

                    // Readers: carried apart, for the badge.
                    val readers =
                        authedService<SocialService>(session.accessToken.value)
                            .bookReadership(BookId("book-paperback"))
                            .shouldSucceed()
                            .readers
                    readers.single().finishes shouldBe emptyList()
                    readers.single().hardcoverFinishes.size shouldBe 1

                    // Uncounted: only the ListenUp finish is a book finished; no feed item for the pulled read.
                    deriveUserStats(sql = sql, userId = userId, nowMs = System.currentTimeMillis()).booksFinished shouldBe 1
                    sql.activitiesQueries
                        .pageFirst(limit = 500)
                        .executeAsList()
                        .filter { it.book_id == "book-paperback" } shouldBe emptyList()
                    sql.activitiesQueries
                        .pageFirst(limit = 500)
                        .executeAsList()
                        .any { it.book_id == "book-audio" && it.type == ActivityType.FINISHED_BOOK } shouldBe true
                }
            } finally {
                fakeHardcover.stop(0, 0)
            }
        }
    })
