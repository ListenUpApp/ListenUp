package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.testing.SyncTestScope
import com.calypsan.listenup.server.testing.domainFrames
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.rows
import com.calypsan.listenup.server.testing.rpcFirehose
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first

/**
 * End-to-end integration test confirming the listening-events + user-stats
 * pipeline rides the per-user sync substrate over the real
 * [com.calypsan.listenup.api.SyncStreamService] RPC surface.
 *
 * Boots the minimal [withTestApplication] harness with `playbackEvents = true`,
 * which wires [com.calypsan.listenup.server.services.ListeningEventRepository]
 * and [com.calypsan.listenup.server.services.UserStatsRepository] directly, and
 * exercises the pull surface through [com.calypsan.listenup.api.SyncStreamService].
 *
 * Asserts four properties:
 *
 * 1. **Domains list** — `listDomains()` includes both
 *    `"listening_events"` and `"user_stats"`.
 *
 * 2. **End-to-end event → stats flow** — a recorded event for u1
 *    delivers a 30-second event; the subsequent sync catch-up and stats read
 *    both reflect it with `totalSecondsAllTime == 30`.
 *
 * 3. **Per-user isolation** — u2's catch-up returns empty; u2's stats read returns null.
 *
 * 4. **RPC firehose isolation** — a u2 event does not leak into u1's firehose
 *    stream ([rpcFirehose] over the harness bus) before u1's own event arrives;
 *    the first `listening_events` frame u1 sees carries u1's book id.
 *
 * The harness names the caller directly — `syncService("u1")` resolves to
 * `UserPrincipal(UserId("u1"))` via the harness's `roleResolver` — there is no
 * bearer-token transport to authenticate.
 */
class ListeningEventAndStatsSyncTest :
    FunSpec({

        /** Wall-clock span for the listening event seeded in cases 2–4. */
        val nowMs = 1_779_451_200_000L
        val wallSeconds = 30L

        fun eventPayload(
            id: String,
            bookId: String,
        ): ListeningEventSyncPayload =
            ListeningEventSyncPayload(
                id = id,
                bookId = bookId,
                startPositionMs = 0L,
                endPositionMs = wallSeconds * 1_000L,
                startedAt = nowMs - wallSeconds * 1_000L,
                endedAt = nowMs,
                playbackSpeed = 1.0f,
                tz = "UTC",
                deviceLabel = null,
                revision = 0L,
                updatedAt = nowMs,
                createdAt = nowMs,
                deletedAt = null,
            )

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

        test("listDomains() includes 'listening_events' and 'user_stats'") {
            withTestApplication(playbackEvents = true) {
                val domains = syncService("u1").listDomains().shouldSucceed()
                domains shouldContain "listening_events"
                domains shouldContain "user_stats"
            }
        }

        test("a recorded listening event materialises into sync catch-up and stats for the owning user") {
            withTestApplication(playbackEvents = true) {
                seedBook("book-a")
                // Record a 30-second listening event as u1.
                val recorded =
                    listeningEventRepo.upsert(eventPayload("evt-u1-1", "book-a"), clientOpId = null, userId = "u1")
                recorded.shouldBeInstanceOf<AppResult.Success<ListeningEventSyncPayload>>()

                // The event must appear in the per-user catch-up page with a non-zero revision.
                val page =
                    syncService("u1").pullDomain("listening_events", since = 0, limit = 100).shouldSucceed()
                val rows = page.rows(ListeningEventSyncPayload.serializer())
                rows.size shouldBe 1
                val event = rows.first()
                event.bookId shouldBe "book-a"
                event.revision shouldBeGreaterThan 0L

                // The stats row must reflect the 30 seconds.
                val stats = userStatsRepo.getForUser("u1").shouldNotBeNull()
                stats.totalSecondsAllTime shouldBe wallSeconds
            }
        }

        test("per-user isolation: u2 sees empty catch-up and no stats after u1 records an event") {
            withTestApplication(playbackEvents = true) {
                seedBook("book-a")
                // Seed u1's event.
                listeningEventRepo.upsert(eventPayload("evt-u1-2", "book-a"), clientOpId = null, userId = "u1")

                // u2's catch-up must return an empty page — u1's event must not leak.
                val u2Page =
                    syncService("u2").pullDomain("listening_events", since = 0, limit = 100).shouldSucceed()
                u2Page.rows(ListeningEventSyncPayload.serializer()).size shouldBe 0

                // u2's stats read returns null (no history yet).
                userStatsRepo.getForUser("u2").shouldBeNull()
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
                listeningEventRepo.upsert(eventPayload("evt-u2-sse", "book-u2"), clientOpId = null, userId = "u2")
                // u1's write — must be the first `listening_events` frame u1 sees.
                listeningEventRepo.upsert(eventPayload("evt-u1-sse", "book-u1"), clientOpId = null, userId = "u1")

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
