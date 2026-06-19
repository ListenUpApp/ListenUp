package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.PublicProfileSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.testing.SyncTestScope
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * Crown-jewel cross-user end-to-end test proving the `public_profiles` domain syncs globally.
 *
 * `public_profiles` is a GLOBAL sync domain: every client's catch-up returns EVERY user's row,
 * regardless of who is asking. This is the structural inverse of `user_stats` (per-user isolation)
 * and proves the leaderboard-of-one bug is architecturally dead — both u1 and u2 see each other's
 * rows via the same catch-up route.
 *
 * Posting a listening event triggers [com.calypsan.listenup.server.services.UserStatsUpdater],
 * which calls [com.calypsan.listenup.server.services.PublicProfileMaintainer.refresh] for the
 * posting user. The maintainer writes that user's projection row to `public_profiles`, which is
 * a global (non-user-scoped) domain — so the catch-up returns all rows to all callers.
 *
 * Contrast: `user_stats` is per-user-scoped; u2's catch-up there returns an empty page after u1
 * posts. Here the assertion is the INVERSE: both u1's and u2's catch-ups return BOTH rows.
 */
class PublicProfileCrossUserRouteTest :
    FunSpec({

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

        test(
            "public_profiles catch-up returns BOTH users' rows for EITHER user (global domain)",
        ) {
            withTestApplication(playbackEvents = true) {
                // Seed user rows so PublicProfileMaintainer.refresh can read displayName/avatarType.
                // Without a users row the maintainer silently no-ops (guard against mid-deletion).
                db.seedTestUser("u1")
                db.seedTestUser("u2")
                seedBook("book-a")

                // Seed u1's listening event — triggers PublicProfileMaintainer for u1.
                val u1Post =
                    client.post("/api/v1/playback/events") {
                        bearerAuth("u1")
                        contentType(ContentType.Application.Json)
                        setBody(recordRequest(id = "evt-u1-xuser", bookId = "book-a"))
                    }
                u1Post.status shouldBe HttpStatusCode.OK

                // Seed u2's listening event — triggers PublicProfileMaintainer for u2.
                val u2Post =
                    client.post("/api/v1/playback/events") {
                        bearerAuth("u2")
                        contentType(ContentType.Application.Json)
                        setBody(recordRequest(id = "evt-u2-xuser", bookId = "book-a"))
                    }
                u2Post.status shouldBe HttpStatusCode.OK

                // u1's catch-up: must see BOTH u1's and u2's public profile rows.
                val u1Response =
                    client.get("/api/v1/sync/public_profiles?since=0") { bearerAuth("u1") }
                u1Response.status shouldBe HttpStatusCode.OK
                val u1Page: Page<PublicProfileSyncPayload> = u1Response.body()
                val u1Ids = u1Page.items.map { it.id }
                u1Ids shouldContain "u1"
                u1Ids shouldContain "u2"

                // u2's catch-up: must also see BOTH rows — the domain is global, not per-user.
                // Contrast: user_stats catch-up for u2 would return only u2's own row (or empty
                // if u1 posted first without u2 posting). public_profiles is the inverse.
                val u2Response =
                    client.get("/api/v1/sync/public_profiles?since=0") { bearerAuth("u2") }
                u2Response.status shouldBe HttpStatusCode.OK
                val u2Page: Page<PublicProfileSyncPayload> = u2Response.body()
                val u2Ids = u2Page.items.map { it.id }
                u2Ids shouldContain "u1"
                u2Ids shouldContain "u2"

                // Secondary assertion: profile rows carry non-trivial stat fields.
                val u1Profile = u1Page.items.first { it.id == "u1" }
                u1Profile.totalSecondsAllTime shouldBe wallSeconds
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
