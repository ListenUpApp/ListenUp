package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode

class SyncCatchUpRouteTest :
    FunSpec({

        test("GET /api/v1/sync/tags?since=0 returns all rows") {
            withTestApplication {
                tagRepo.upsert(Tag("a", "alpha", "alpha", 0, 0))
                tagRepo.upsert(Tag("b", "beta", "beta", 0, 0))

                val response = client.get("/api/v1/sync/tags?since=0")
                response.status shouldBe HttpStatusCode.OK
                val page: Page<Tag> = response.body()
                page.items shouldHaveSize 2
                page.items.map { it.id } shouldBe listOf("a", "b")
            }
        }

        test("unknown domain returns 404") {
            withTestApplication {
                val response = client.get("/api/v1/sync/nonexistent?since=0")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("malformed cursor returns 400") {
            withTestApplication {
                val response = client.get("/api/v1/sync/tags?since=not-a-number")
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("a targeted ?ids= fetch over the 100-id cap returns 400") {
            withTestApplication {
                // The cap is enforced before the repo read, so it fires on any registered domain.
                val ids = (1..101).joinToString(",") { "id-$it" }
                val response = client.get("/api/v1/sync/tags?ids=$ids")
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("a targeted ?collectionIds= fetch over the 100-id cap returns 400") {
            withTestApplication {
                val ids = (1..101).joinToString(",") { "col-$it" }
                val response = client.get("/api/v1/sync/tags?collectionIds=$ids")
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("a ?bookIds= fetch on a domain without a book_id column returns 400 (allowlist keeps matchColumn sound)") {
            withTestApplication {
                // `tags` has no `book_id` column, so honoring the fetch would be a SQL error — the
                // per-domain allowlist rejects it before the repo read.
                val response = client.get("/api/v1/sync/tags?bookIds=b1,b2")
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("a ?bookIds= fetch over the 100-id cap returns 400 on activities") {
            withTestApplication(playbackEvents = true) {
                // activities is on the allowlist, so this reaches the id-cap guard.
                val ids = (1..101).joinToString(",") { "book-$it" }
                val response = client.get("/api/v1/sync/activities?bookIds=$ids")
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("a targeted ?ids= fetch on a GLOBAL ungated domain (tags) returns the requested row (DRIFT-1 heal path)") {
            withTestApplication {
                tagRepo.upsert(Tag("a", "alpha", "alpha", 0, 0))
                tagRepo.upsert(Tag("b", "beta", "beta", 0, 0))

                // `tags` is a GLOBAL ungated domain: every row is visible to every authenticated
                // caller, so a by-id fetch serves the rows directly — this is the read the client's
                // DRIFT-1 dead-letter heal uses to re-fetch current server truth for a curation entity.
                // Only the requested id comes back.
                val response = client.get("/api/v1/sync/tags?ids=a")
                response.status shouldBe HttpStatusCode.OK
                response.body<Page<Tag>>().items.map { it.id } shouldBe listOf("a")
            }
        }

        test("a ?ids= fetch on a userScoped domain returns 200 empty even for an existing row (no cross-user leak)") {
            // A userScoped domain has no access-filter driver, so an unfiltered by-id read would leak
            // another user's rows. It must therefore answer an EMPTY page for `?ids=` — even when the
            // row genuinely exists — rather than serving it (its convergence rides `?since=`). Also the
            // original invariant: a valid authenticated sync GET never 500s.
            withTestApplication(playbackPositions = true) {
                val seeded =
                    playbackPositionRepo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 1_000L,
                        lastPlayedAt = 0L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                val positionId = (seeded as AppResult.Success).data.id

                val response = client.get("/api/v1/sync/playback_positions?ids=$positionId")
                response.status shouldBe HttpStatusCode.OK
                response.body<Page<PlaybackPositionSyncPayload>>().items.shouldBeEmpty()
            }
        }
    })
