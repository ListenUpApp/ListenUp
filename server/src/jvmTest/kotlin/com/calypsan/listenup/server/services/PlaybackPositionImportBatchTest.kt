@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Equivalence characterization for [PlaybackPositionRepository.recordAllForImport]: the batched
 * import write must leave the database in EXACTLY the state a per-row [recordPosition] loop would
 * (the "bulk == N×single" pattern PR #994 used for reindex batching). Both paths run against
 * separate in-memory databases seeded identically, under a [FixedClock] so timestamps are
 * deterministic and the read-back rows are comparable (the surrogate `id` is random per row, so it
 * is the one field excluded from the comparison).
 *
 * The fixture mixes every branch the wins-guard + id-reuse must preserve: a fresh insert, a
 * stale-older row (dropped against a fresher baseline), an intra-batch finished-flip (a second row
 * for the same book reusing the first's surrogate id), an intra-batch stale duplicate, and rows for
 * a second user.
 */
class PlaybackPositionImportBatchTest :
    FunSpec({

        test("recordAllForImport leaves the same position rows as an N×single recordPosition loop") {
            withSqlDatabase {
                val singleDbs = this
                withSqlDatabase {
                    val batchDbs = this
                    runTest {
                        val clock = FixedClock(Instant.fromEpochMilliseconds(1_000_000L))
                        val single =
                            PlaybackPositionRepository(
                                db = singleDbs.sql,
                                bus = ChangeBus(),
                                registry = SyncRegistry(),
                                clock = clock,
                            )
                        val batch =
                            PlaybackPositionRepository(
                                db = batchDbs.sql,
                                bus = ChangeBus(),
                                registry = SyncRegistry(),
                                clock = clock,
                            )

                        // Identical pre-existing baseline in both DBs — the wins-guard needs a row that
                        // is already fresher (lastPlayedAt 5000) than an incoming import row.
                        listOf(single, batch).forEach { repo ->
                            repo.recordPosition(
                                userId = "uA",
                                bookId = "book-stale",
                                positionMs = 100L,
                                lastPlayedAt = 5_000L,
                                finished = false,
                                playbackSpeed = 1.0f,
                                currentChapterId = null,
                            )
                        }

                        // Note: the position batch writes in fixture order (it groups only for the read),
                        // so users may interleave freely without diverging revisions from the single loop.
                        val fixture =
                            listOf(
                                ImportPositionWrite("uA", "book-fresh", 200L, 1_000L, false, 1.0f, null),
                                ImportPositionWrite("uA", "book-stale", 999L, 3_000L, false, 1.0f, null),
                                ImportPositionWrite("uA", "book-flip", 300L, 1_000L, false, 1.0f, null),
                                ImportPositionWrite("uA", "book-flip", 400L, 2_000L, true, 1.0f, null),
                                ImportPositionWrite("uA", "book-dup", 500L, 2_000L, false, 1.0f, null),
                                ImportPositionWrite("uA", "book-dup", 501L, 1_000L, false, 1.0f, null),
                                ImportPositionWrite("uB", "book-fresh", 210L, 1_000L, false, 1.0f, null),
                                ImportPositionWrite("uA", "book-finished", 600L, 9_000L, true, 1.0f, null),
                                ImportPositionWrite("uA", "book-plain", 700L, 4_000L, false, 1.25f, "chap-2"),
                                ImportPositionWrite("uB", "book-x", 800L, 7_000L, true, 1.0f, null),
                            )

                        fixture.forEach { row ->
                            single.recordPosition(
                                userId = row.userId,
                                bookId = row.bookId,
                                positionMs = row.positionMs,
                                lastPlayedAt = row.lastPlayedAt,
                                finished = row.finished,
                                playbackSpeed = row.playbackSpeed,
                                currentChapterId = row.currentChapterId,
                                startedBookOccurredAt = row.startedBookOccurredAt,
                            )
                        }
                        batch.recordAllForImport(fixture)

                        listOf("uA", "uB").forEach { user ->
                            positionSnapshot(single, user) shouldBe positionSnapshot(batch, user)
                        }
                    }
                }
            }
        }
    })

/**
 * A user's live positions projected to a stable, comparable form — every domain + sync-discipline
 * field except the random surrogate `id`, sorted so two databases written in the same order compare
 * value-for-value.
 */
private suspend fun positionSnapshot(
    repo: PlaybackPositionRepository,
    userId: String,
): List<String> =
    repo
        .listForUser(UserId(userId), limit = 1_000)
        .map {
            listOf(
                it.bookId,
                it.positionMs,
                it.lastPlayedAt,
                it.finished,
                it.playbackSpeed,
                it.currentChapterId,
                it.revision,
                it.createdAt,
                it.updatedAt,
                it.deletedAt,
            ).joinToString("|")
        }.sorted()
