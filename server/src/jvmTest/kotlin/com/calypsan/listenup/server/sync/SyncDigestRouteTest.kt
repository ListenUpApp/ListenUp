package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode

class SyncDigestRouteTest :
    FunSpec({

        test("GET /api/v1/sync/tags/digest returns DomainDigest") {
            withTestApplication {
                tagRepo.upsert(Tag("a", "alpha", "alpha", 0, 0))
                tagRepo.upsert(Tag("b", "beta", "beta", 0, 0))

                val response = client.get("/api/v1/sync/tags/digest?cursor=999")
                response.status shouldBe HttpStatusCode.OK
                val d: DomainDigest = response.body()
                d.count shouldBe 2
                d.hash shouldStartWith "sha256:"
            }
        }

        test("unknown domain returns 404") {
            withTestApplication {
                val response = client.get("/api/v1/sync/nope/digest?cursor=0")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }
    })
