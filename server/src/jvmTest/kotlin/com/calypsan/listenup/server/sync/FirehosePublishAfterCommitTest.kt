package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.domainFrames
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.rpcFirehose
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

class FirehosePublishAfterCommitTest :
    FunSpec({

        test("a rolled-back write publishes no firehose event") {
            withTestApplication {
                // Seed a tag holding the slug "dup-slug" (its name is asserted-against neither
                // way). The seed's own Created event is harmless — only "ghost"/"real" matter.
                tagRepo.upsert(Tag("seed", "seed-name", "dup-slug", 0, 0))

                // A write whose own SQLDelight transaction rolls back: the duplicate slug
                // violates the partial-unique index, the insert throws inside the repo's
                // transactionWithResult, SQLDelight rolls back, and the afterCommit emit
                // never fires — its event must NEVER reach the firehose.
                runCatching {
                    tagRepo.upsert(Tag("rolled-back", "ghost", "dup-slug", 0, 0))
                }
                // A committed control the subscriber WILL receive — proves the stream is live.
                tagRepo.upsert(Tag("committed", "real", "real", 0, 0))

                // The bus's replay buffer holds every published event, so subscribing after
                // the writes is deterministic: the first "ghost"-or-"real" frame must be
                // "real" — a phantom "ghost" publish would replay first and fail this.
                val frame =
                    rpcFirehose(bus, rootPrincipal("test-user"))
                        .domainFrames()
                        .first {
                            it.domain == "tags" &&
                                (
                                    it.json.contains(""""name":"real"""") ||
                                        it.json.contains(""""name":"ghost"""")
                                )
                        }
                frame.json.contains(""""name":"real"""") shouldBe true
                frame.json.contains(""""name":"ghost"""") shouldBe false
            }
        }

        test("concurrent writes are published to the firehose in ascending revision order") {
            withTestApplication {
                // Fire 8 writes CONCURRENTLY. Their published revisions can only come out
                // ascending because nextRevision() serializes on the sync_meta row lock and
                // the deferred emit fires synchronously at each commit — a serial driver would
                // hide a reordering regression, so the concurrency is the point.
                coroutineScope {
                    repeat(8) { i -> launch { tagRepo.upsert(Tag("tag-$i", "n$i", "n$i", 0, 0)) } }
                }
                // The bus replays in publish order, so the first 8 `tags` frames collected
                // after the writes ARE the arrival order a live subscriber would have seen.
                val revisions =
                    rpcFirehose(bus, rootPrincipal("test-user"))
                        .domainFrames()
                        .filter { it.domain == "tags" }
                        .take(8)
                        .toList()
                        .mapNotNull { it.revision }
                revisions.size shouldBe 8
                revisions shouldBe revisions.sorted()
            }
        }
    })
