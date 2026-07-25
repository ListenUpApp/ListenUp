package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.server.testing.domainFrames
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.rows
import com.calypsan.listenup.server.testing.rpcFirehose
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first

/**
 * Integration guard confirming [PlaybackPositionRepository] composes correctly
 * with the per-user sync substrate over the real [com.calypsan.listenup.api.SyncStreamService]
 * RPC surface.
 *
 * Boots the minimal [withTestApplication] harness with `playbackPositions = true`,
 * seeds positions directly via the repository, and asserts:
 *
 * 1. `listDomains()` lists `"playback_positions"` among the
 *    registered domains — the repository self-registered at startup.
 *
 * 2. **Per-user catch-up isolation**: `pullDomain("playback_positions", since = 0, ...)`
 *    for u1 returns only u1's row; the same call for u2 returns only u2's row.
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
 * The harness names the caller directly — `syncService("u1")` resolves to
 * `UserPrincipal(UserId("u1"))` via the harness's `roleResolver` — there is no
 * bearer-token transport to authenticate.
 */
class PlaybackPositionSyncPullTest :
    FunSpec({

        test("listDomains() includes 'playback_positions' when the repository is wired") {
            withTestApplication(playbackPositions = true) {
                val domains = syncService("u1").listDomains().shouldSucceed()
                domains shouldContain "playback_positions"
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

                val u1Page =
                    syncService("u1").pullDomain("playback_positions", since = 0, limit = 100).shouldSucceed()
                val u1Rows = u1Page.rows(PlaybackPositionSyncPayload.serializer())
                u1Rows shouldHaveSize 2
                u1Rows.map { it.bookId }.toSet() shouldBe setOf("book-a", "book-b")

                val u2Page =
                    syncService("u2").pullDomain("playback_positions", since = 0, limit = 100).shouldSucceed()
                val u2Rows = u2Page.rows(PlaybackPositionSyncPayload.serializer())
                u2Rows shouldHaveSize 1
                u2Rows.first().bookId shouldBe "book-a"
                u2Rows.first().positionMs shouldBe 99_000L
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
