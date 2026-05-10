package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.sse.sse
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Verifies the SSE firehose emits a comment-line keepalive heartbeat at the
 * configured cadence. NAT and load-balancer drops on idle TCP connections are
 * the failure mode this defends against; in production the cadence is 25s,
 * but the test runs at 200ms so the suite stays fast.
 */
class SyncFirehoseHeartbeatTest :
    FunSpec({

        test("SSE firehose emits a comment-line heartbeat at the configured interval") {
            withTestApplication(heartbeatIntervalMillis = 200L) {
                // showCommentEvents = true: the Ktor client filters comment-only events
                // out of `incoming` by default. We turn that off so the heartbeat is
                // observable here. Production clients leave it filtered — the comment
                // frame is wire-only, just enough bytes to keep the connection alive.
                client.sse(
                    urlString = "/api/v1/sync/events",
                    showCommentEvents = true,
                ) {
                    coroutineScope {
                        val firstHeartbeat =
                            withTimeout(2.seconds) {
                                async {
                                    incoming.first { it.comments?.isNotBlank() == true }
                                }.await()
                            }
                        firstHeartbeat.comments?.trim() shouldBe "keepalive"
                    }
                }
            }
        }
    })
