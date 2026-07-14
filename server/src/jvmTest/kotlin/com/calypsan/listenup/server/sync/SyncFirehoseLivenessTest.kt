package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.sse.sse
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pins the C2 firehose gate: revoking a session mid-stream severs its LIVE SSE firehose within the
 * poll window with a terminal `SyncControl.StreamError(SessionExpired)` — the JWT auth wall only
 * re-checks liveness at the UPGRADE, so without this a revoked device keeps receiving the sync tail
 * until the socket drops. Runs the poll at 200ms so the suite stays fast.
 */
class SyncFirehoseLivenessTest :
    FunSpec({

        test("a revoked session severs the live firehose with a SessionExpired control frame") {
            val alive = AtomicBoolean(true)
            withTestApplication(
                sessionLiveness = { _ -> alive.get() },
                livenessPollMillis = 200L,
            ) {
                client.sse("/api/v1/sync/events") {
                    // The session is revoked while the firehose is live.
                    alive.set(false)

                    val control = withTimeout(3.seconds) { incoming.first { it.event == "control" } }
                    control.data!! shouldContain "SyncControl.StreamError"
                    control.data!! shouldContain "AuthError.SessionExpired"
                }
            }
        }

        test("a live session's firehose is not severed by the gate") {
            withTestApplication(
                sessionLiveness = { _ -> true },
                livenessPollMillis = 200L,
                heartbeatIntervalMillis = 100L,
            ) {
                client.sse(urlString = "/api/v1/sync/events", showCommentEvents = true) {
                    // Several poll windows elapse; a live session must never see a StreamError —
                    // the first non-empty frame is a keepalive comment, not a control frame.
                    val frame =
                        withTimeout(3.seconds) {
                            incoming.first { it.comments?.isNotBlank() == true || it.event == "control" }
                        }
                    frame.event shouldBe null // not a "control"/StreamError severance
                    frame.comments?.trim() shouldBe "keepalive"
                }
            }
        }
    })
