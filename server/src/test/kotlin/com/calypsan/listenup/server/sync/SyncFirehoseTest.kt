package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

class SyncFirehoseTest :
    FunSpec({

        test("SSE emits Tag events on the firehose with event:tags lines") {
            withTestApplication {
                client.sse("/api/v1/sync/events") {
                    coroutineScope {
                        val deferred = async { incoming.first() }
                        // Trigger a write that publishes to the bus
                        tagRepo.upsert(Tag("a", "alpha", 0, 0))
                        val event = deferred.await()
                        event.event shouldBe "tags"
                        event.id shouldBe "1" // first revision
                        event.data!! shouldContain """"type":"SyncEvent.Created""""
                        event.data!! shouldContain """"name":"alpha""""
                    }
                }
            }
        }

        test("SSE with stale Last-Event-Id emits CursorStale and closes") {
            withTestApplication {
                // Publish 300 events to overflow the 256-event replay buffer.
                // After overflow, the buffer holds revisions 45–300; revision 1 is evicted.
                repeat(300) { i -> tagRepo.upsert(Tag("tag-$i", "label-$i", 0, 0)) }

                // Connect with Last-Event-Id=1 — older than the buffer floor (~45).
                // client cursor < oldestRetained → stale.
                client.sse(
                    urlString = "/api/v1/sync/events",
                    request = { headers { append(HttpHeaders.LastEventID, "1") } },
                ) {
                    val event = incoming.first()
                    event.event shouldBe "control"
                    event.data!! shouldContain """"type":"SyncControl.CursorStale""""
                }
            }
        }
    })
