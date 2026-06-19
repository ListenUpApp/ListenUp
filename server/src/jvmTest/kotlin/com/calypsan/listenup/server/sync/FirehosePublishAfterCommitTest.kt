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
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

class FirehosePublishAfterCommitTest :
    FunSpec({

        test("a rolled-back write publishes no firehose event") {
            withTestApplication {
                client.sse("/api/v1/sync/events") {
                    coroutineScope {
                        val firstTagEvent = async { incoming.first { it.event == "tags" } }

                        // A write that rolls back: its event must NEVER reach the firehose.
                        runCatching {
                            suspendTransaction(db) {
                                tagRepo.upsert(Tag("rolled-back", "ghost", "ghost", 0, 0))
                                error("boom")
                            }
                        }
                        // A committed control the member WILL receive — proves the stream is live.
                        tagRepo.upsert(Tag("committed", "real", "real", 0, 0))

                        val event = firstTagEvent.await()
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
