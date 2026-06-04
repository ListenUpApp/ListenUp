package com.calypsan.listenup.api.dto.import

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.ImportId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Lifecycle status of a staged ABS import, derived from which staging files exist. */
@Serializable
enum class ImportStatus {
    UPLOADED,
    ANALYZED,
    MAPPED,
    APPLIED,
}

/** Why/how strongly an ABS entity matched a ListenUp entity. */
@Serializable
enum class MatchTier {
    ASIN,
    ISBN,
    PATH,
    TITLE_AUTHOR,
    STRONG,
    AMBIGUOUS,
    UNMATCHED,
}

/**
 * Summary of one staged ABS import job.
 *
 * Reflects the filesystem-truth state of the import directory under
 * `$LISTENUP_HOME/imports/<id>/`. [status] is derived from which staging files
 * are present; [bookCount] and [userCount] are read from `analysis.json` when
 * available, or zero for freshly uploaded (UPLOADED status) jobs.
 */
@Serializable
@SerialName("ImportSummary")
data class ImportSummary(
    val id: ImportId,
    val createdAt: Long,
    val status: ImportStatus,
    val bookCount: Int,
    val userCount: Int,
)

/**
 * A suggested ABS-user → ListenUp-user mapping, produced by [com.calypsan.listenup.api.ImportService.analyze].
 *
 * [confidence] reflects how the match was made: [MatchTier.STRONG] means exact email or
 * username match; [MatchTier.AMBIGUOUS] means multiple ListenUp users matched; [MatchTier.UNMATCHED]
 * means no candidate was found. [suggestedUserId] is null when confidence is AMBIGUOUS or UNMATCHED.
 */
@Serializable
@SerialName("AbsUserMatch")
data class AbsUserMatch(
    val absUserId: AbsUserId,
    val absUsername: String,
    val absEmail: String?,
    val suggestedUserId: UserId?,
    val confidence: MatchTier,
)

/**
 * A reference to an ABS library item shown in the import preview.
 *
 * Used in [ImportAnalysis.ambiguous] and [ImportAnalysis.unmatched] to surface
 * items that require admin attention before apply. The admin can supply manual
 * [bookOverrides][com.calypsan.listenup.api.ImportService.confirmMapping] for these items
 * or leave them as null to skip them.
 */
@Serializable
@SerialName("AbsItemRef")
data class AbsItemRef(
    val absItemId: AbsItemId,
    val title: String,
    val asin: String?,
    val isbn: String?,
    val relPath: String?,
)

/**
 * Read-only preview produced by [com.calypsan.listenup.api.ImportService.analyze].
 *
 * Summarises confidence-tiered matching results so an admin can review before
 * calling [com.calypsan.listenup.api.ImportService.confirmMapping]. Items in
 * [ambiguous] and [unmatched] can be resolved via manual book overrides or skipped.
 * [importableSessionCount] is the number of ABS playback sessions that can be
 * imported as listening events (filtered to book-type sessions with a matched item).
 */
@Serializable
@SerialName("ImportAnalysis")
data class ImportAnalysis(
    val userMatches: List<AbsUserMatch>,
    val bookMatchCounts: Map<MatchTier, Int>,
    val ambiguous: List<AbsItemRef>,
    val unmatched: List<AbsItemRef>,
    val importableSessionCount: Int = 0,
)

/**
 * Outcome of a completed [com.calypsan.listenup.api.ImportService.apply] operation.
 *
 * [importedCount] is the total number of progress records written; [sessionsImported]
 * is the number of ABS playback sessions written as listening events; [skippedCount]
 * is the number skipped (unmapped user, no matched/overridden book, or manual null override).
 * [perUser] breaks down imported counts by ListenUp user ID.
 */
@Serializable
@SerialName("ImportResult")
data class ImportResult(
    val importedCount: Int,
    val sessionsImported: Int = 0,
    val skippedCount: Int,
    val perUser: Map<UserId, Int>,
)

/**
 * Progress events streamed during ABS import analyze and apply operations via
 * [com.calypsan.listenup.api.ImportService.observeProgress].
 *
 * Consumers drive progress UI by folding over the sealed hierarchy. [Analyzed] and
 * [Applied] carry structured results; [Failed] carries a human-readable reason string.
 */
@Serializable
sealed interface ImportEvent {
    /** The ABS backup zip is being parsed and the SQLite extracted. */
    @Serializable
    @SerialName("ImportEvent.Parsing")
    data object Parsing : ImportEvent

    /** Book items are being matched against the ListenUp library. */
    @Serializable
    @SerialName("ImportEvent.Matching")
    data class Matching(
        val done: Int,
        val total: Int,
    ) : ImportEvent

    /** Analysis is complete; the preview summary is ready for admin review. */
    @Serializable
    @SerialName("ImportEvent.Analyzed")
    data class Analyzed(
        val summary: ImportSummary,
    ) : ImportEvent

    /** Progress records are being written to the ListenUp database. */
    @Serializable
    @SerialName("ImportEvent.Applying")
    data class Applying(
        val done: Int,
        val total: Int,
    ) : ImportEvent

    /** Apply completed successfully; all eligible progress records have been written. */
    @Serializable
    @SerialName("ImportEvent.Applied")
    data class Applied(
        val result: ImportResult,
    ) : ImportEvent

    /** The operation failed; the import job remains staged for retry or deletion. */
    @Serializable
    @SerialName("ImportEvent.Failed")
    data class Failed(
        val reason: String,
    ) : ImportEvent
}
