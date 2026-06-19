package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
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
    })
