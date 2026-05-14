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

/**
 * Pins the malformed-cursor branch of the SSE firehose: a non-numeric
 * `Last-Event-ID` is treated identically to a stale cursor, forcing the client
 * into REST catch-up rather than silently subscribing to the live tail and
 * diverging from server state (corrupted Room cell, client-side bug, etc.).
 */
class SyncRoutesMalformedCursorTest :
    FunSpec({

        test("SSE with non-numeric Last-Event-ID emits CursorStale and closes") {
            withTestApplication {
                client.sse(
                    urlString = "/api/v1/sync/events",
                    request = {
                        headers { append(HttpHeaders.LastEventID, "garbage-not-a-number") }
                    },
                ) {
                    val event = incoming.first()
                    event.event shouldBe "control"
                    event.data!! shouldContain """"type":"SyncControl.CursorStale""""
                    event.data!! shouldContain """"lastKnownRevision":"""
                }
            }
        }

        test("SSE with missing Last-Event-ID subscribes normally (no CursorStale)") {
            withTestApplication {
                client.sse("/api/v1/sync/events") {
                    coroutineScope {
                        val deferred = async { incoming.first() }
                        // Drive a write so the firehose has something to emit; if the route
                        // were misclassifying a missing header as malformed, the first frame
                        // would be a control/CursorStale instead of a tags event.
                        tagRepo.upsert(Tag("a", "alpha", 0, 0))
                        val event = deferred.await()
                        event.event shouldBe "tags"
                        event.data!! shouldContain """"type":"SyncEvent.Created""""
                    }
                }
            }
        }
    })
