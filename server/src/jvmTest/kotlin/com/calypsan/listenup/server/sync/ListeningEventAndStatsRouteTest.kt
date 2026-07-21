package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.testing.SyncTestScope
import com.calypsan.listenup.server.testing.domainFrames
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.rpcFirehose
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.first

/**
 * End-to-end integration test confirming the listening-events + user-stats
 * pipeline rides the per-user sync substrate over real HTTP routes.
 *
 * Boots the minimal [withTestApplication] harness with `playbackEvents = true`,
 * which wires [com.calypsan.listenup.server.services.ListeningEventRepository],
 * [com.calypsan.listenup.server.services.UserStatsRepository], and
 * [com.calypsan.listenup.server.api.PlaybackServiceImpl] and mounts both
 * [com.calypsan.listenup.server.sync.syncRoutes] and
 * [com.calypsan.listenup.server.routes.playbackRoutes].
 *
 * Asserts four properties:
 *
 * 1. **Domains list** — `GET /api/v1/sync/domains` includes both
 *    `"listening_events"` and `"user_stats"`.
 *
 * 2. **End-to-end event → stats flow** — `POST /api/v1/playback/events` for u1
 *    delivers a 30-second event; the subsequent sync catch-up and stats read
 *    both reflect it with `totalSecondsAllTime == 30`.
 *
 * 3. **Per-user isolation** — u2's catch-up returns empty; u2's stats return 204.
 *
 * 4. **RPC firehose isolation** — a u2 event does not leak into u1's firehose
 *    stream ([rpcFirehose] over the harness bus) before u1's own event arrives;
 *    the first `listening_events` frame u1 sees carries u1's book id.
 *
 * The [testAuth] provider authenticates the bearer token verbatim as the user id,
 * so `bearerAuth("u1")` resolves to `UserPrincipal(UserId("u1"))`.
 */
class ListeningEventAndStatsRouteTest :
    FunSpec({

        /** Wall-clock span for the listening event seeded in cases 2–4. */
        val nowMs = 1_779_451_200_000L
        val wallSeconds = 30L

        fun recordRequest(
            id: String,
            bookId: String,
        ): RecordListeningEventRequest =
            RecordListeningEventRequest(
                id = id,
                bookId = bookId,
                startPositionMs = 0L,
                endPositionMs = wallSeconds * 1_000L,
                startedAt = nowMs - wallSeconds * 1_000L,
                endedAt = nowMs,
                playbackSpeed = 1.0f,
                tz = "UTC",
                deviceLabel = null,
            )

        test("GET /api/v1/sync/domains lists 'listening_events' and 'user_stats'") {
            withTestApplication(playbackEvents = true) {
                val response = client.get("/api/v1/sync/domains") { bearerAuth("u1") }
                response.status shouldBe HttpStatusCode.OK
                val domains: DomainList = response.body()
                domains.domains shouldContain "listening_events"
                domains.domains shouldContain "user_stats"
            }
        }

        test("POST /api/v1/playback/events materialises into sync catch-up and stats for the owning user") {
            withTestApplication(playbackEvents = true) {
                seedBook("book-a")
                // Record a 30-second listening event as u1.
                val postResponse =
                    client.post("/api/v1/playback/events") {
                        bearerAuth("u1")
                        contentType(ContentType.Application.Json)
                        setBody(recordRequest(id = "evt-u1-1", bookId = "book-a"))
                    }
                postResponse.status shouldBe HttpStatusCode.OK

                // The event must appear in the per-user catch-up page with a non-zero revision.
                val catchUpResponse =
                    client.get("/api/v1/sync/listening_events?since=0") { bearerAuth("u1") }
                catchUpResponse.status shouldBe HttpStatusCode.OK
                val page: Page<ListeningEventSyncPayload> = catchUpResponse.body()
                page.items.size shouldBe 1
                val event = page.items.first()
                event.bookId shouldBe "book-a"
                event.revision shouldBeGreaterThan 0L

                // The stats row must reflect the 30 seconds.
                val statsResponse = client.get("/api/v1/playback/stats") { bearerAuth("u1") }
                statsResponse.status shouldBe HttpStatusCode.OK
                val stats: UserStatsSyncPayload = statsResponse.body()
                stats.totalSecondsAllTime shouldBe wallSeconds
            }
        }

        test("per-user isolation: u2 sees empty catch-up and no stats after u1 records an event") {
            withTestApplication(playbackEvents = true) {
                seedBook("book-a")
                // Seed u1's event.
                client.post("/api/v1/playback/events") {
                    bearerAuth("u1")
                    contentType(ContentType.Application.Json)
                    setBody(recordRequest(id = "evt-u1-2", bookId = "book-a"))
                }

                // u2's catch-up must return an empty page — u1's event must not leak.
                val u2CatchUp =
                    client.get("/api/v1/sync/listening_events?since=0") { bearerAuth("u2") }
                u2CatchUp.status shouldBe HttpStatusCode.OK
                val u2Page: Page<ListeningEventSyncPayload> = u2CatchUp.body()
                u2Page.items.size shouldBe 0

                // u2's stats endpoint returns 204 (no history yet).
                val u2Stats = client.get("/api/v1/playback/stats") { bearerAuth("u2") }
                u2Stats.status shouldBe HttpStatusCode.NoContent
            }
        }

        test("RPC firehose delivers a listening_events event to its owning user, not to another user") {
            withTestApplication(playbackEvents = true) {
                seedBook("book-u1")
                seedBook("book-u2")
                // Write both events first, then observe as u1: the bus's replay buffer
                // holds both, so the collection is deterministic. u2's write is skipped
                // for the u1 subscriber; a leaked u2 event would arrive first and the
                // book-id assertion below would see "book-u2" instead of "book-u1".
                client.post("/api/v1/playback/events") {
                    bearerAuth("u2")
                    contentType(ContentType.Application.Json)
                    setBody(recordRequest(id = "evt-u2-sse", bookId = "book-u2"))
                }
                // u1's write — must be the first `listening_events` frame u1 sees.
                client.post("/api/v1/playback/events") {
                    bearerAuth("u1")
                    contentType(ContentType.Application.Json)
                    setBody(recordRequest(id = "evt-u1-sse", bookId = "book-u1"))
                }

                val frame =
                    rpcFirehose(bus, rootPrincipal("u1"))
                        .domainFrames()
                        .first { it.domain == "listening_events" }
                frame.json.contains(""""book-u1"""") shouldBe true
            }
        }
    })

/**
 * Upserts a minimal accessible book so the playback access gate admits events
 * recorded against [id]. The harness pre-seeds `test-library` / `test-folder`.
 */
private suspend fun SyncTestScope.seedBook(id: String) {
    bookRepo.upsert(
        BookSyncPayload(
            id = id,
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = id,
            sortTitle = id,
            subtitle = null,
            description = null,
            publishYear = null,
            publisher = null,
            language = null,
            isbn = null,
            asin = null,
            abridged = false,
            explicit = false,
            totalDuration = 60_000L,
            cover = null,
            rootRelPath = "books/$id",
            inode = null,
            scannedAt = 1L,
            contributors = emptyList(),
            series = emptyList(),
            audioFiles = emptyList(),
            chapters = emptyList(),
            revision = 0L,
            updatedAt = 0L,
            createdAt = 0L,
            deletedAt = null,
        ),
    )
}
