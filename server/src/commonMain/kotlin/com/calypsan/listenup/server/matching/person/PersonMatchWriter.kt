package com.calypsan.listenup.server.matching.person

import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.ContributorField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.hardcover.HARDCOVER_AUTHOR_KEY_PREFIX
import com.calypsan.listenup.server.matching.undo.ContributorMatchSnapshot
import com.calypsan.listenup.server.matching.undo.MatchReceiptCodec
import com.calypsan.listenup.server.matching.undo.MatchReceiptRow
import com.calypsan.listenup.server.matching.undo.MatchReceiptStore
import com.calypsan.listenup.server.matching.undo.ReceiptEntity
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.sync.FrameCapture
import kotlinx.coroutines.currentCoroutineContext
import kotlin.uuid.Uuid

private const val HARDCOVER = "hardcover"

/**
 * Writes a [PersonMatchPlan] in **one** transaction: the pre-apply snapshot and the receipt, then the person —
 * biography, photo path, refs, the asin column and provenance — at one new revision. [basedOnRevision] is
 * re-checked inside the transaction: a person who moved since (or was merged away) is
 * [MetadataError.ReviewOutdated], and nothing is written.
 */
internal class PersonMatchWriter(
    private val db: ListenUpDatabase,
    private val contributors: ContributorRepository,
    private val receipts: MatchReceiptStore,
    private val now: () -> Long,
    /** Runs just before commit; the rollback test throws from it. Production passes nothing. */
    private val beforeCommit: () -> Unit = {},
) {
    suspend fun write(
        plan: PersonMatchPlan,
        basedOnRevision: Long,
        appliedBy: String,
        at: Long,
    ): AppResult<MatchReceipt> {
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null
        val capture = currentCoroutineContext()[FrameCapture.Key]
        val id = plan.contributorId
        return suspendTransaction(db) {
            val before = contributors.readPayloadInTransaction(id)?.takeIf { it.deletedAt == null }
            if (before == null || before.revision != basedOnRevision) {
                return@suspendTransaction AppResult.Failure(
                    MetadataError.ReviewOutdated(
                        debugInfo = "person $id is at ${before?.revision ?: "no revision"}, not $basedOnRevision",
                    ),
                )
            }
            val revisionAfter = contributors.allocateRevision()
            val receipt =
                MatchReceipt(
                    receiptId = Uuid.random().toString(),
                    appliedAt = now(),
                    changes = plan.changes,
                    undoable = true,
                )
            receipts.replaceLiveInTransaction(
                MatchReceiptRow(
                    id = receipt.receiptId,
                    entity = ReceiptEntity.CONTRIBUTOR.value,
                    entityId = id,
                    appliedBy = appliedBy,
                    appliedAt = receipt.appliedAt,
                    revisionAfter = revisionAfter,
                    snapshotJson = MatchReceiptCodec.encodeContributorSnapshot(ContributorMatchSnapshot(before)),
                    changes = plan.changes,
                    undoneAt = null,
                ),
            )
            contributors.writeMatchInTransaction(
                tx = this,
                value = before.matchedBy(plan, at),
                revision = revisionAfter,
                suppressed = suppressed,
                capture = capture,
            )
            beforeCommit()
            AppResult.Success(receipt)
        }
    }
}

/**
 * The person after [plan]: the chosen bio and photo stamped `ENRICHMENT` from their providers, the candidate's
 * refs in place of those providers' refs (the others kept), and the asin column following them — it names the
 * Audible ref when the candidate has one, moves a legacy `hardcover:author:` key to the new Hardcover id, and is
 * otherwise left, because the repository rebuilds the ref the column names on every write.
 */
private fun ContributorSyncPayload.matchedBy(
    plan: PersonMatchPlan,
    at: Long,
): ContributorSyncPayload {
    val providers = plan.refs.map { it.provider }.toSet()
    val audible = plan.refs.firstOrNull { it.provider == ExternalRef.AUDIBLE }
    val hardcover = plan.refs.firstOrNull { it.provider == HARDCOVER }
    val column =
        when {
            audible != null -> {
                audible.id
            }

            hardcover != null && asin?.run { trim().startsWith(HARDCOVER_AUTHOR_KEY_PREFIX) } == true -> {
                HARDCOVER_AUTHOR_KEY_PREFIX + hardcover.id
            }

            else -> {
                asin
            }
        }
    val stamps =
        listOfNotNull(
            plan.photo?.let { photo ->
                ContributorField.PHOTO to
                    FieldProvenance(FieldSourceKind.ENRICHMENT, photo.provider.value, at)
            },
            plan.biography?.let {
                ContributorField.BIOGRAPHY to FieldProvenance(FieldSourceKind.ENRICHMENT, it.provider.value, at)
            },
        )
    return copy(
        description = plan.biography?.text ?: description,
        imagePath = plan.photo?.path ?: imagePath,
        asin = column,
        externalRefs = externalRefs.filter { it.provider !in providers } + plan.refs,
        fieldProvenance = fieldProvenance + stamps,
    )
}
