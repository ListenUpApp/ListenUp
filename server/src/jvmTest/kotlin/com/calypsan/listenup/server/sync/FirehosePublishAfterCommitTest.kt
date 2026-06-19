package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.sse.sse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

class FirehosePublishAfterCommitTest :
    FunSpec({

        test("a rolled-back write publishes no firehose event") {
            withTestApplication {
                // Seed a tag holding the slug "dup-slug" (its name is asserted-against neither
                // way). The seed's own Created event is harmless — only "ghost"/"real" matter.
                tagRepo.upsert(Tag("seed", "seed-name", "dup-slug", 0, 0))
                client.sse("/api/v1/sync/events") {
                    coroutineScope {
                        val firstGhostOrReal =
                            async {
                                incoming.first {
                                    it.event == "tags" &&
                                        (
                                            it.data?.contains(""""name":"real"""") == true ||
                                                it.data?.contains(""""name":"ghost"""") == true
                                        )
                                }
                            }

                        // A write whose own SQLDelight transaction rolls back: the duplicate slug
                        // violates the partial-unique index, the insert throws inside the repo's
                        // transactionWithResult, SQLDelight rolls back, and the afterCommit emit
                        // never fires — its event must NEVER reach the firehose.
                        runCatching {
                            tagRepo.upsert(Tag("rolled-back", "ghost", "dup-slug", 0, 0))
                        }
                        // A committed control the member WILL receive — proves the stream is live.
                        tagRepo.upsert(Tag("committed", "real", "real", 0, 0))

                        val event = firstGhostOrReal.await()
                        event.data!!.contains(""""name":"real"""") shouldBe true
                        event.data!!.contains(""""name":"ghost"""") shouldBe false
                    }
                }
            }
        }

        test("concurrent writes are published to the firehose in ascending revision order") {
            withTestApplication {
                client.sse("/api/v1/sync/events") {
                    coroutineScope {
                        // Collect the first 8 `tags` events in arrival order.
                        val firstEight = async { incoming.filter { it.event == "tags" }.take(8).toList() }
                        // Fire 8 writes CONCURRENTLY. Their published revisions can only come out
                        // ascending because nextRevision() serializes on the sync_meta row lock and
                        // the deferred emit fires synchronously at each commit — a serial driver would
                        // hide a reordering regression, so the concurrency is the point.
                        coroutineScope {
                            repeat(8) { i -> launch { tagRepo.upsert(Tag("tag-$i", "n$i", "n$i", 0, 0)) } }
                        }
                        val revisions = firstEight.await().mapNotNull { it.id?.toLongOrNull() }
                        revisions.size shouldBe 8
                        revisions shouldBe revisions.sorted()
                    }
                }
            }
        }
    })
