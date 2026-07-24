package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest

/**
 * Pins [SeriesDao.digestRows]: the query must return the LIVE rows (soft-deleted tombstones
 * EXCLUDED) whose `revision <= maxRevision`, mapped as `(id, revision)` pairs.
 *
 * Three scenarios:
 *  1. Live rows within max → included.
 *  2. Tombstoned rows within max → EXCLUDED (the digest counts only live rows, so a client that
 *     tombstoned a row locally converges with the server's tombstone-excluding digest — F1).
 *  3. Rows whose revision exceeds max → excluded.
 */
class SeriesDigestRowsTest :
    FunSpec({

        test("digestRows returns live rows and EXCLUDES tombstones, bounded by maxRevision") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val dao = db.seriesDao()

                    // revision 1 — live
                    dao.upsert(seriesEntityWithRevision("s-live-1", "Live Series One", revision = 1L))
                    // revision 2 — soft-deleted (tombstone must NOT appear in the digest)
                    dao.upsert(seriesEntityWithRevision("s-dead-2", "Deleted Series", revision = 2L))
                    dao.softDelete(SeriesId("s-dead-2"), deletedAt = 1_000L, revision = 2L)
                    // revision 5 — live but above the max we'll query
                    dao.upsert(seriesEntityWithRevision("s-live-5", "Future Series", revision = 5L))

                    // Query with max = 3 → should see only s-live-1 (rev 1); s-dead-2 is tombstoned.
                    val rows = dao.digestRows(max = 3L)

                    rows shouldHaveSize 1
                    rows.map { it.id to it.revision } shouldContainExactlyInAnyOrder
                        listOf(
                            "s-live-1" to 1L,
                        )
                } finally {
                    db.close()
                }
            }
        }

        test("digestRows excludes rows whose revision exceeds maxRevision") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val dao = db.seriesDao()
                    dao.upsert(seriesEntityWithRevision("s-1", "Series A", revision = 10L))
                    dao.upsert(seriesEntityWithRevision("s-2", "Series B", revision = 20L))

                    val rows = dao.digestRows(max = 10L)

                    rows shouldHaveSize 1
                    rows.first().id shouldBe "s-1"
                } finally {
                    db.close()
                }
            }
        }

        test("digestRows returns empty list when no rows are within maxRevision") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val dao = db.seriesDao()
                    dao.upsert(seriesEntityWithRevision("s-1", "High Rev Series", revision = 100L))

                    dao.digestRows(max = 1L) shouldHaveSize 0
                } finally {
                    db.close()
                }
            }
        }
    })

private infix fun String.shouldBe(expected: String) {
    if (this != expected) throw AssertionError("Expected <$expected> but was <$this>")
}

private fun seriesEntityWithRevision(
    id: String,
    name: String,
    revision: Long,
) = SeriesEntity(
    id = SeriesId(id),
    name = name,
    description = null,
    revision = revision,
    createdAt = Timestamp(1L),
    updatedAt = Timestamp(1L),
)
