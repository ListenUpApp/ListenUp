package com.calypsan.listenup.server.sync

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

        test("a targeted ?ids= fetch on a global ungated domain (tags) returns 200 with an empty page, not 500") {
            withTestApplication {
                tagRepo.upsert(Tag("a", "alpha", "alpha", 0, 0))

                // `tags` is not access-gated, so it wires no SqlDriver and cannot serve the
                // access-filtered targeted read `pullByIds` performs. It degrades to an empty page
                // rather than throwing (which surfaced as a 500): these domains are served via the
                // `?since=` catch-up, and the client no longer issues `?ids=` for them (reconcile-on-
                // drain is gated to access-filtered domains). The invariant that matters here is that a
                // valid authenticated sync GET never 500s.
                val response = client.get("/api/v1/sync/tags?ids=a")
                response.status shouldBe HttpStatusCode.OK
                response.body<Page<Tag>>().items.shouldBeEmpty()
            }
        }

        test("a targeted ?ids= fetch on the userScoped playback_positions domain returns 200 empty, not 500") {
            // Reproduces the reconcile-on-drain 500: every position push triggered a client
            // `GET /api/v1/sync/playback_positions?ids=…`, and the userScoped repo has no driver.
            withTestApplication(playbackPositions = true) {
                val response = client.get("/api/v1/sync/playback_positions?ids=some-position-id")
                response.status shouldBe HttpStatusCode.OK
                response.body<Page<PlaybackPositionSyncPayload>>().items.shouldBeEmpty()
            }
        }
    })
