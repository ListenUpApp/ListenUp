package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.server.testing.domainFrames
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.rpcFirehose
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first

/**
 * Integration guard confirming [PlaybackPositionRepository] composes correctly
 * with the per-user sync substrate over the real HTTP routes.
 *
 * Boots the minimal [withTestApplication] harness with `playbackPositions = true`,
 * seeds positions directly via the repository, and asserts:
 *
 * 1. `GET /api/v1/sync/domains` lists `"playback_positions"` among the
 *    registered domains — the repository self-registered at startup.
 *
 * 2. **Per-user catch-up isolation**: `GET /api/v1/sync/playback_positions?since=0`
 *    for u1 returns only u1's row; the same route for u2 returns only u2's row.
 *    Neither user sees the other's data.
 *
 * 3. **Per-user RPC firehose isolation**: u1's firehose stream ([rpcFirehose]
 *    over the harness bus) receives the event for u1's position write and does
 *    NOT see u2's write first — a leaked u2 event would arrive first and fail
 *    the data-content assertion.
 *
 * Seeding is done via [PlaybackPositionRepository.recordPosition] (direct repo
 * call), which is the same path [PlaybackServiceImpl] uses at runtime, making
 * this a genuine end-to-end smoke of the substrate.
 *
 * The [testAuth] provider authenticates the bearer token verbatim as the
 * user id, so `bearerAuth("u1")` resolves to `UserPrincipal(UserId("u1"))`.
 */
class PlaybackPositionSyncRouteTest :
    FunSpec({

        test("GET /api/v1/sync/domains lists 'playback_positions' when the repository is wired") {
            withTestApplication(playbackPositions = true) {
                val response = client.get("/api/v1/sync/domains") { bearerAuth("u1") }
                response.status shouldBe HttpStatusCode.OK
                val domains: DomainList = response.body()
                domains.domains shouldContain "playback_positions"
            }
        }

        test("catch-up route returns only the authenticated user's positions") {
            withTestApplication(playbackPositions = true) {
                playbackPositionRepo.recordPosition(
                    userId = "u1",
                    bookId = "book-a",
                    positionMs = 10_000L,
                    lastPlayedAt = 1_730_000_000_000L,
                    finished = false,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )
                playbackPositionRepo.recordPosition(
                    userId = "u1",
                    bookId = "book-b",
                    positionMs = 20_000L,
                    lastPlayedAt = 1_730_000_000_001L,
                    finished = false,
                    playbackSpeed = 1.25f,
                    currentChapterId = "chap-1",
                )
                playbackPositionRepo.recordPosition(
                    userId = "u2",
                    bookId = "book-a",
                    positionMs = 99_000L,
                    lastPlayedAt = 1_730_000_000_002L,
                    finished = true,
                    playbackSpeed = 1.5f,
                    currentChapterId = null,
                )

                val u1Response =
                    client.get("/api/v1/sync/playback_positions?since=0") { bearerAuth("u1") }
                u1Response.status shouldBe HttpStatusCode.OK
                val u1Page: Page<PlaybackPositionSyncPayload> = u1Response.body()
                u1Page.items shouldHaveSize 2
                u1Page.items.map { it.bookId }.toSet() shouldBe setOf("book-a", "book-b")

                val u2Response =
                    client.get("/api/v1/sync/playback_positions?since=0") { bearerAuth("u2") }
                u2Response.status shouldBe HttpStatusCode.OK
                val u2Page: Page<PlaybackPositionSyncPayload> = u2Response.body()
                u2Page.items shouldHaveSize 1
                u2Page.items.first().bookId shouldBe "book-a"
                u2Page.items.first().positionMs shouldBe 99_000L
            }
        }

        test("RPC firehose delivers a position event to its owning user, not to another user") {
            withTestApplication(playbackPositions = true) {
                // Write first, then observe as u1: the bus's replay buffer holds both
                // writes, so the collection is deterministic. u2's write must be
                // filtered out of the u1 stream; if it leaked, it would arrive first
                // and the data-content check below would see u2's bookId ("book-u2")
                // instead of u1's ("book-u1").
                playbackPositionRepo.recordPosition(
                    userId = "u2",
                    bookId = "book-u2",
                    positionMs = 5_000L,
                    lastPlayedAt = 1_730_000_000_000L,
                    finished = false,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )
                playbackPositionRepo.recordPosition(
                    userId = "u1",
                    bookId = "book-u1",
                    positionMs = 42_000L,
                    lastPlayedAt = 1_730_000_000_001L,
                    finished = false,
                    playbackSpeed = 1.25f,
                    currentChapterId = "chap-1",
                )

                val frame =
                    rpcFirehose(bus, rootPrincipal("u1"))
                        .domainFrames()
                        .first { it.domain == "playback_positions" }
                // The first playback_positions frame u1 sees must be u1's own row
                frame.json.contains(""""book-u1"""") shouldBe true
            }
        }
    })
