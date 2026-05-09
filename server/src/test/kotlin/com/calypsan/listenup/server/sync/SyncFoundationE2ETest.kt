package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.call.body
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

class SyncFoundationE2ETest :
    FunSpec({

        test("end-to-end lifecycle: SSE connect → write → receive → reconnect → REST catch-up") {
            withTestApplication {
                // ---- Phase 1: Connect SSE, write, assert receive ----
                var firstReceivedRevision: Long = 0

                client.sse("/api/v1/sync/events") {
                    coroutineScope {
                        val deferred = async { incoming.first() }
                        tagRepo.upsert(Tag("t1", "alpha", 0, 0))
                        val event = deferred.await()
                        event.event shouldBe "tags"
                        event.data!! shouldContain """"type":"SyncEvent.Created""""
                        event.data!! shouldContain """"id":"t1""""
                        firstReceivedRevision = event.id!!.toLong()
                    }
                }

                // ---- Phase 2: Write while disconnected ----
                tagRepo.upsert(Tag("t2", "beta", 0, 0))

                // ---- Phase 3: Reconnect with Last-Event-Id, write fresh t3, assert receive ----
                client.sse(
                    urlString = "/api/v1/sync/events",
                    request = {
                        headers { append(HttpHeaders.LastEventID, firstReceivedRevision.toString()) }
                    },
                ) {
                    coroutineScope {
                        val deferred = async { incoming.first() }
                        tagRepo.upsert(Tag("t3", "gamma", 0, 0))
                        val event3 = deferred.await()
                        event3.data!! shouldContain """"id":"t3""""
                    }
                }

                // ---- Phase 4: Verify domain-list discovery ----
                val list: DomainList = client.get("/api/v1/sync/domains").body()
                list.domains shouldBe listOf("tags")

                // ---- Phase 5: Verify digest ----
                val digest: DomainDigest = client.get("/api/v1/sync/tags/digest?cursor=999").body()
                digest.count shouldBe 3 // t1, t2, t3

                // ---- Phase 6: REST catch-up returns all rows ----
                val page: Page<Tag> = client.get("/api/v1/sync/tags?since=0").body()
                page.items shouldHaveSize 3

                // ---- Phase 7: Soft-delete a row, assert tombstone in catch-up ----
                tagRepo.softDelete("t1")
                val pageAfterDelete: Page<Tag> = client.get("/api/v1/sync/tags?since=0").body()
                val t1 = pageAfterDelete.items.first { it.id == "t1" }
                t1.deletedAt shouldNotBe null
            }
        }
    })
