package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.Tag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Pins [AppendOnlyMirrorApply]'s three-way convergence policy directly (KDoc: load-bearing
 * for digest convergence). Substrate is a recording in-memory fake; the Room-backed
 * re-delivery paths are covered per domain (AccessChangedReconcileTest for activities,
 * ListeningEventsDomainTest for listening_events).
 *
 * Assertions target the recorded call lists (which branch fired), not just end state — that
 * is what makes the branch selection un-fakeable (end-state-only checks would pass even if,
 * say, [restore] were replaced by [insert]-then-[updateRevision]).
 */
private class RecordingAppendOnlyApply : AppendOnlyMirrorApply<Tag>() {
    val rows = mutableMapOf<String, AppendOnlyRowMeta>()
    val inserts = mutableListOf<Tag>()
    val restores = mutableListOf<Pair<String, Long>>()
    val revisionUpdates = mutableListOf<Pair<String, Long>>()

    override suspend fun insert(payload: Tag) {
        inserts += payload
        rows[payload.id] = AppendOnlyRowMeta(deletedAt = payload.deletedAt, revision = payload.revision)
    }

    override suspend fun readMeta(id: String): AppendOnlyRowMeta? = rows[id]

    override suspend fun restore(
        id: String,
        revision: Long,
    ) {
        restores += id to revision
        rows[id] = AppendOnlyRowMeta(deletedAt = null, revision = revision)
    }

    override suspend fun updateRevision(
        id: String,
        revision: Long,
    ) {
        revisionUpdates += id to revision
        rows[id] = rows.getValue(id).copy(revision = revision)
    }

    override suspend fun tombstoneFromItem(item: Tag) {
        rows[item.id] = AppendOnlyRowMeta(deletedAt = item.deletedAt ?: item.updatedAt, revision = item.revision)
    }
}

private fun tag(
    id: String = "t1",
    revision: Long = 1L,
    deletedAt: Long? = null,
) = Tag(id = id, name = "n", slug = "s", revision = revision, updatedAt = 100L, deletedAt = deletedAt)

class AppendOnlyMirrorApplyTest :
    FunSpec({
        test("absent id → insert, and only insert") {
            val apply = RecordingAppendOnlyApply()
            apply.upsert(tag("t1"))
            apply.inserts.map { it.id } shouldContainExactly listOf("t1")
            apply.restores.shouldBeEmpty()
            apply.revisionUpdates.shouldBeEmpty()
        }

        test("tombstoned row + LIVE re-delivery → restore with the payload's revision (the digest-healing branch)") {
            val apply = RecordingAppendOnlyApply()
            apply.rows["t1"] = AppendOnlyRowMeta(deletedAt = 50L, revision = 1L)
            apply.upsert(tag("t1", revision = 2L))
            apply.restores shouldContainExactly listOf("t1" to 2L)
            apply.inserts.shouldBeEmpty()
            apply.revisionUpdates.shouldBeEmpty()
            apply.rows.getValue("t1").deletedAt shouldBe null
        }

        test("tombstoned row + LIVE re-delivery at the SAME revision still restores") {
            // The exact stranded-tombstone case the class KDoc warns about: digests already
            // agree on (id, revision), so ONLY this branch can heal it.
            val apply = RecordingAppendOnlyApply()
            apply.rows["t1"] = AppendOnlyRowMeta(deletedAt = 50L, revision = 1L)
            apply.upsert(tag("t1", revision = 1L))
            apply.restores shouldContainExactly listOf("t1" to 1L)
            apply.inserts.shouldBeEmpty()
            apply.revisionUpdates.shouldBeEmpty()
            apply.rows.getValue("t1").deletedAt shouldBe null
        }

        test("live row + differing revision → updateRevision only (idempotent replay/backfill)") {
            val apply = RecordingAppendOnlyApply()
            apply.rows["t1"] = AppendOnlyRowMeta(deletedAt = null, revision = 1L)
            apply.upsert(tag("t1", revision = 3L))
            apply.revisionUpdates shouldContainExactly listOf("t1" to 3L)
            apply.inserts.shouldBeEmpty()
            apply.restores.shouldBeEmpty()
        }

        test("live row + same revision → complete no-op") {
            val apply = RecordingAppendOnlyApply()
            val seeded = AppendOnlyRowMeta(deletedAt = null, revision = 1L)
            apply.rows["t1"] = seeded
            apply.upsert(tag("t1", revision = 1L))
            apply.inserts.shouldBeEmpty()
            apply.restores.shouldBeEmpty()
            apply.revisionUpdates.shouldBeEmpty()
            apply.rows.getValue("t1") shouldBe seeded
        }

        test("tombstoned row + TOMBSTONED re-delivery converges revision WITHOUT restoring") {
            val apply = RecordingAppendOnlyApply()
            apply.rows["t1"] = AppendOnlyRowMeta(deletedAt = 50L, revision = 1L)
            apply.upsert(tag("t1", revision = 2L, deletedAt = 60L))
            apply.restores.shouldBeEmpty()
            apply.revisionUpdates shouldContainExactly listOf("t1" to 2L)
            apply.inserts.shouldBeEmpty()
            apply.rows.getValue("t1").deletedAt shouldBe 50L
        }
    })
