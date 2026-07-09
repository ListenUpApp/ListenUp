@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Equivalence characterization for [ListeningEventRepository.upsertAllForImport]: the batched import
 * write must leave the database in EXACTLY the state a per-row [ListeningEventRepository.upsert] loop
 * would (the "bulk == N×single" pattern PR #994 used for reindex batching). Both paths run against
 * separate in-memory databases seeded identically, under a [FixedClock] so timestamps are
 * deterministic and every read-back field — id, domain fields, and the sync-discipline revision —
 * compares value-for-value.
 *
 * The fixture mixes every branch the append-only upsert + first-insert bookkeeping must preserve: a
 * fresh insert, a re-upsert of a pre-existing id (revision advances, domain fields frozen), an
 * intra-batch duplicate id (second occurrence advances sync columns only), and rows for a second
 * user. The batch groups its writes by user, so the fixture is grouped (all `uA`, then all `uB`) to
 * keep the revision sequence identical to the ungrouped single loop.
 */
class ListeningEventImportBatchTest :
    FunSpec({

        test("upsertAllForImport leaves the same event rows as an N×single upsert loop") {
            withSqlDatabase {
                val singleDbs = this
                withSqlDatabase {
                    val batchDbs = this
                    runTest {
                        val clock = FixedClock(Instant.fromEpochMilliseconds(1_000_000L))
                        val single =
                            ListeningEventRepository(
                                db = singleDbs.sql,
                                bus = ChangeBus(),
                                registry = SyncRegistry(),
                                clock = clock,
                            )
                        val batch =
                            ListeningEventRepository(
                                db = batchDbs.sql,
                                bus = ChangeBus(),
                                registry = SyncRegistry(),
                                clock = clock,
                            )

                        // Identical pre-existing event in both DBs — a re-applied import re-upserts it.
                        listOf(single to "uA", batch to "uA").forEach { (repo, user) ->
                            repo.upsert(eventPayload("evt-seed", "book-seed"), clientOpId = null, userId = user)
                        }

                        val fixture =
                            listOf(
                                ImportListeningEventWrite("uA", eventPayload("evt-1", "book-1")),
                                ImportListeningEventWrite("uA", eventPayload("evt-2", "book-2")),
                                // Re-upsert of the seeded id — append-only, domain fields must not change.
                                ImportListeningEventWrite("uA", eventPayload("evt-seed", "book-seed", startPositionMs = 9_999L)),
                                ImportListeningEventWrite("uA", eventPayload("evt-3", "book-3")),
                                // Intra-batch duplicate id — the second occurrence is not a first insert.
                                ImportListeningEventWrite("uA", eventPayload("evt-3", "book-3", startPositionMs = 7_777L)),
                                ImportListeningEventWrite("uB", eventPayload("evt-4", "book-4")),
                                ImportListeningEventWrite("uB", eventPayload("evt-5", "book-5")),
                            )

                        fixture.forEach { write ->
                            single.upsert(write.event, clientOpId = null, userId = write.userId)
                        }
                        batch.upsertAllForImport(fixture)

                        listOf("uA", "uB").forEach { user ->
                            eventSnapshot(singleDbs.sql, user) shouldBe eventSnapshot(batchDbs.sql, user)
                        }
                    }
                }
            }
        }
    })

/** A user's live listening events projected to a stable, comparable per-row string, sorted by id. */
private fun eventSnapshot(
    sql: ListenUpDatabase,
    userId: String,
): List<String> =
    sql.listeningEventsQueries
        .selectForUserOrderedByEndedAt(userId)
        .executeAsList()
        .map {
            listOf(
                it.id,
                it.book_id,
                it.start_position_ms,
                it.end_position_ms,
                it.started_at,
                it.ended_at,
                it.playback_speed,
                it.tz,
                it.device_label,
                it.revision,
                it.created_at,
                it.updated_at,
                it.deleted_at,
            ).joinToString("|")
        }.sorted()

private fun eventPayload(
    id: String,
    bookId: String,
    startPositionMs: Long = 0L,
): ListeningEventSyncPayload =
    ListeningEventSyncPayload(
        id = id,
        bookId = bookId,
        startPositionMs = startPositionMs,
        endPositionMs = startPositionMs + 60_000L,
        startedAt = 1_730_000_000_000L,
        endedAt = 1_730_000_060_000L,
        playbackSpeed = 1.0f,
        tz = "Europe/London",
        deviceLabel = null,
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
