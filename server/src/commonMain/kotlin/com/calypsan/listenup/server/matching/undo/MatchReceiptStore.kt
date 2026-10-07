package com.calypsan.listenup.server.matching.undo

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.LastMatch
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Match_receipts
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** The entity kinds a receipt can be for. */
internal enum class ReceiptEntity(
    val value: String,
) {
    BOOK("book"),
    CONTRIBUTOR("contributor"),
}

/** A book's cover columns as they were before a match. A null [source] means it had no cover. */
@Serializable
@SerialName("SnapshotCover")
internal data class SnapshotCover(
    @SerialName("source") val source: String?,
    @SerialName("path") val path: String?,
    @SerialName("hash") val hash: String?,
)

/**
 * Everything a book match touched, as it was before (spec, *Undo*). [book] is the whole pre-apply aggregate
 * (Undo only runs while the revision is unchanged, so restoring the untouched parts is a no-op). Mood links are a
 * sync domain of their own and don't move the book's revision, so Undo reverses only what the match did to them:
 * it unlinks [moodsLinked] and relinks [moodsUnlinked].
 */
@Serializable
@SerialName("BookMatchSnapshot")
internal data class BookMatchSnapshot(
    @SerialName("book") val book: BookSyncPayload,
    @SerialName("cover") val cover: SnapshotCover,
    @SerialName("genreIds") val genreIds: List<String>,
    @SerialName("moodsLinked") val moodsLinked: List<String>,
    @SerialName("moodsUnlinked") val moodsUnlinked: List<String>,
)

/**
 * Everything a person match touched, as it was before: the whole pre-apply aggregate (bio, photo path, refs, the
 * asin column, provenance). Undo only runs while the revision is unchanged, so restoring the rest is a no-op. The
 * orphan sweep keeps [contributor]'s photo file while the receipt is live.
 */
@Serializable
@SerialName("ContributorMatchSnapshot")
internal data class ContributorMatchSnapshot(
    @SerialName("contributor") val contributor: ContributorSyncPayload,
)

/** One stored receipt, decoded. */
internal data class MatchReceiptRow(
    val id: String,
    val entity: String,
    val entityId: String,
    val appliedBy: String,
    val appliedAt: Long,
    val revisionAfter: Long,
    val snapshotJson: String,
    val changes: List<AppliedChange>,
    val undoneAt: Long?,
)

private val CHANGES = ListSerializer(AppliedChange.serializer())

/** Encodes and decodes a receipt's JSON columns. */
internal object MatchReceiptCodec {
    fun encodeChanges(changes: List<AppliedChange>): String = contractJson.encodeToString(CHANGES, changes)

    fun decodeChanges(json: String): List<AppliedChange> = contractJson.decodeFromString(CHANGES, json)

    fun encodeSnapshot(snapshot: BookMatchSnapshot): String =
        contractJson.encodeToString(BookMatchSnapshot.serializer(), snapshot)

    fun decodeSnapshot(json: String): BookMatchSnapshot =
        contractJson.decodeFromString(BookMatchSnapshot.serializer(), json)

    fun encodeContributorSnapshot(snapshot: ContributorMatchSnapshot): String =
        contractJson.encodeToString(ContributorMatchSnapshot.serializer(), snapshot)

    fun decodeContributorSnapshot(json: String): ContributorMatchSnapshot =
        contractJson.decodeFromString(ContributorMatchSnapshot.serializer(), json)

    /** A live book receipt as the book's sync payload carries it, only while it can still be undone. */
    fun lastMatchOf(
        row: Match_receipts,
        bookRevision: Long,
    ): LastMatch? =
        if (row.undone_at == null && row.revision_after == bookRevision) {
            LastMatch(row.id, row.applied_at, row.applied_by, row.revision_after, decodeChanges(row.changes))
        } else {
            null
        }
}

/**
 * The `match_receipts` table (V90): one live receipt per entity, the Undo token, and what the orphan sweep must
 * keep. Writes run inside the caller's transaction, so a receipt lands with its match or not at all.
 */
internal class MatchReceiptStore(
    private val db: ListenUpDatabase,
) {
    /** Records [row] as [ReceiptEntity]'s live receipt, replacing any earlier one. Inside an open transaction. */
    fun replaceLiveInTransaction(row: MatchReceiptRow) {
        db.matchReceiptsQueries.deleteLiveForEntity(row.entity, row.entityId)
        db.matchReceiptsQueries.insert(
            id = row.id,
            entity_kind = row.entity,
            entity_id = row.entityId,
            applied_by = row.appliedBy,
            applied_at = row.appliedAt,
            revision_after = row.revisionAfter,
            snapshot = row.snapshotJson,
            changes = MatchReceiptCodec.encodeChanges(row.changes),
        )
    }

    /** The receipt [id], or null. Inside an open transaction. */
    fun findInTransaction(id: String): MatchReceiptRow? =
        db.matchReceiptsQueries.selectById(id).executeAsOneOrNull()?.let {
            MatchReceiptRow(
                id = it.id,
                entity = it.entity_kind,
                entityId = it.entity_id,
                appliedBy = it.applied_by,
                appliedAt = it.applied_at,
                revisionAfter = it.revision_after,
                snapshotJson = it.snapshot,
                changes = MatchReceiptCodec.decodeChanges(it.changes),
                undoneAt = it.undone_at,
            )
        }

    /** The receipt [id], or null. */
    suspend fun find(id: String): MatchReceiptRow? = suspendTransaction(db) { findInTransaction(id) }

    /** Marks [id] undone at [now]. Inside an open transaction. */
    fun markUndoneInTransaction(
        id: String,
        now: Long,
    ) = db.matchReceiptsQueries.markUndone(undone_at = now, id = id)

    /**
     * Every cover path a live receipt's snapshot names — files the orphan sweep must keep so Undo can restore
     * them. Throws when a snapshot can't be read: a sweep that can't tell what is pinned must delete nothing.
     */
    suspend fun pinnedCoverPaths(): Set<String> =
        suspendTransaction(db) {
            db.matchReceiptsQueries
                .selectLiveSnapshotsForKind(ReceiptEntity.BOOK.value)
                .executeAsList()
                .mapNotNullTo(mutableSetOf()) { MatchReceiptCodec.decodeSnapshot(it).cover.path }
        }

    /**
     * Every contributor photo path a live person receipt's snapshot names — `contributors/` files the orphan sweep
     * must keep so Undo can restore them. Throws when a snapshot can't be read, so the sweep deletes nothing.
     */
    suspend fun pinnedPhotoPaths(): Set<String> =
        suspendTransaction(db) {
            db.matchReceiptsQueries
                .selectLiveSnapshotsForKind(ReceiptEntity.CONTRIBUTOR.value)
                .executeAsList()
                .mapNotNullTo(mutableSetOf()) { MatchReceiptCodec.decodeContributorSnapshot(it).contributor.imagePath }
        }

    /** Deletes receipts that can no longer be undone: undone, or their book or person has changed or gone. */
    suspend fun deleteDead(): Unit =
        suspendTransaction(db) {
            db.matchReceiptsQueries.deleteDeadBookReceipts()
            db.matchReceiptsQueries.deleteDeadContributorReceipts()
        }
}
