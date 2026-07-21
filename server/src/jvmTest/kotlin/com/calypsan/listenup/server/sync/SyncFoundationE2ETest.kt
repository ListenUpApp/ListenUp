package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.domainFrames
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.rpcFirehose
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

/**
 * End-to-end lifecycle of the sync foundation: the RPC firehose ([rpcFirehose]
 * over the harness bus) for the live tail — observe, write, receive, then
 * resume via `sinceRevision` — followed by the REST discovery/digest/catch-up
 * routes and the soft-delete tombstone.
 */
class SyncFoundationE2ETest :
    FunSpec({

        test("end-to-end lifecycle: RPC stream → write → receive → resume with sinceRevision → REST catch-up") {
            withTestApplication {
                // ---- Step 1: Write, observe the firehose, assert receive ----
                // The bus's replay buffer holds the write, so subscribing after it is
                // deterministic — a live subscriber would have seen the same frame.
                tagRepo.upsert(Tag("t1", "alpha", "alpha", 0, 0))
                val firstFrame =
                    rpcFirehose(bus, rootPrincipal("test-user"))
                        .domainFrames()
                        .first { it.domain == "tags" }
                firstFrame.json shouldContain """"type":"SyncEvent.Created""""
                firstFrame.json shouldContain """"id":"t1""""
                val firstReceivedRevision = firstFrame.revision!!

                // ---- Step 2: Write while disconnected ----
                tagRepo.upsert(Tag("t2", "beta", "beta", 0, 0))

                // ---- Step 3: Resubscribe with sinceRevision, verify replay + live tail ----
                // With replay=256, resuming with sinceRevision = t1's revision delivers t2
                // (missed while disconnected) from the replay cache, then t3. This is the
                // desired catch-up behavior — the client doesn't need REST for events still
                // in the bus buffer.
                tagRepo.upsert(Tag("t3", "gamma", "gamma", 0, 0))
                val resumed =
                    rpcFirehose(bus, rootPrincipal("test-user"), sinceRevision = firstReceivedRevision)
                        .domainFrames()
                        .take(2)
                        .toList()
                resumed[0].json shouldContain """"id":"t2""""
                resumed[1].json shouldContain """"id":"t3""""

                // ---- Step 4: Verify domain-list discovery ----
                val list: DomainList = client.get("/api/v1/sync/domains").body()
                list.domains shouldBe listOf("tags")

                // ---- Step 5: Verify digest ----
                val digest: DomainDigest = client.get("/api/v1/sync/tags/digest?cursor=999").body()
                digest.count shouldBe 3 // t1, t2, t3

                // ---- Step 6: REST catch-up returns all rows ----
                val page: Page<Tag> = client.get("/api/v1/sync/tags?since=0").body()
                page.items shouldHaveSize 3

                // ---- Step 7: Soft-delete a row, assert tombstone in catch-up ----
                tagRepo.softDelete("t1")
                val pageAfterDelete: Page<Tag> = client.get("/api/v1/sync/tags?since=0").body()
                val t1 = pageAfterDelete.items.first { it.id == "t1" }
                t1.deletedAt shouldNotBe null
            }
        }
    })
