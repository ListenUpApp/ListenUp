package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.dto.scan.ScanIssueReason
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The reads [com.calypsan.listenup.api.ScannerService] needs from the scan-issue record.
 *
 * A port rather than the concrete repository so the service stays testable without standing up a
 * database — the same shape as [WatcherSupervisorPort] next door, and for the same reason.
 */
interface ScanIssueStore {
    /** Open issues for [libraryId], oldest first. */
    suspend fun listOpen(libraryId: LibraryId): List<ScanIssue>

    /** Stops showing [issueId]. Idempotent. */
    suspend fun dismiss(issueId: String)
}

/**
 * The durable record of folders the scanner walked but could not import.
 *
 * This exists because the alternative was a `logger.warn` line. A book that failed to import was
 * simply absent — no row, no notice, nothing the user could act on or even discover. Recording the
 * failure is what turns "it didn't show up and I don't know why" into a sentence the app can say
 * out loud.
 *
 * Not a syncable domain, deliberately: an issue names a filesystem path, which is operator
 * information rather than library content, and exactly one admin screen reads it. Making it
 * syncable would buy a revision cursor and a tombstone obligation nothing wants.
 */
class ScanIssueRepository(
    private val db: ListenUpDatabase,
    private val clock: Clock = Clock.System,
) : ScanIssueStore {
    /**
     * Records that [rootRelPath] failed to import, or refreshes the existing record if it already
     * had. `first_seen_at` never moves; everything else does.
     */
    suspend fun record(
        libraryId: LibraryId,
        rootRelPath: String,
        reason: ScanIssueReason,
        detail: String?,
    ) {
        val now = clock.now().toEpochMilliseconds()
        suspendTransaction(db) {
            db.scanIssuesQueries.insertIfAbsent(
                id = Uuid.random().toString(),
                library_id = libraryId.value,
                root_rel_path = rootRelPath,
                reason = reason.name,
                detail = detail,
                seen_at = now,
            )
            db.scanIssuesQueries.refresh(
                reason = reason.name,
                detail = detail,
                seen_at = now,
                library_id = libraryId.value,
                root_rel_path = rootRelPath,
            )
        }
    }

    /**
     * Forgets any issue recorded against [rootRelPath] — the folder scanned cleanly this time.
     *
     * The row is deleted rather than marked dismissed: the problem is *gone*, not acknowledged,
     * and leaving a tombstone would make a fixed folder look like an ignored one.
     */
    suspend fun clear(
        libraryId: LibraryId,
        rootRelPath: String,
    ) {
        suspendTransaction(db) {
            db.scanIssuesQueries.deleteByPath(library_id = libraryId.value, root_rel_path = rootRelPath)
        }
    }

    override suspend fun listOpen(libraryId: LibraryId): List<ScanIssue> =
        suspendTransaction(db) {
            db.scanIssuesQueries
                .selectOpen(libraryId.value)
                .executeAsList()
                .map { row ->
                    ScanIssue(
                        id = row.id,
                        rootRelPath = row.root_rel_path,
                        reason = row.reason.toReason(),
                        detail = row.detail,
                        firstSeenAt = row.first_seen_at,
                        lastSeenAt = row.last_seen_at,
                    )
                }
        }

    override suspend fun dismiss(issueId: String) {
        suspendTransaction(db) {
            db.scanIssuesQueries.dismissById(
                dismissed_at = clock.now().toEpochMilliseconds(),
                id = issueId,
            )
        }
    }
}

/**
 * Folds a stored reason string back to its enum.
 *
 * An unrecognised value means the row was written by a newer server than the one reading it. That
 * is a legitimate downgrade, not corruption, so it degrades to [ScanIssueReason.UNKNOWN] — the
 * issue still gets shown with its detail text, which is the part the user acts on anyway.
 */
private fun String.toReason(): ScanIssueReason =
    ScanIssueReason.entries.firstOrNull { it.name == this } ?: ScanIssueReason.UNKNOWN

/**
 * Classifies a scan failure into the reason an admin would act on.
 *
 * The mapping is deliberately lossy: [ScanError] distinguishes faults by where they happened,
 * while an issue distinguishes them by what the user should do about it. Anything that does not
 * imply a distinct action folds to [ScanIssueReason.UNKNOWN] and leans on `detail` instead.
 */
fun ScanError.toIssueReason(): ScanIssueReason =
    when (this) {
        is ScanError.FileUnreadable -> ScanIssueReason.FILE_UNREADABLE
        is ScanError.MetadataParseError -> ScanIssueReason.METADATA_PARSE_FAILED
        is ScanError.TitleInferenceError -> ScanIssueReason.TITLE_INFERENCE_FAILED
        else -> ScanIssueReason.UNKNOWN
    }

/**
 * The library-relative path a [ScanError] blames, or null for a whole-scan fault.
 *
 * Only path-bearing errors become issues. A library root that is unreachable is a server
 * configuration problem, not one broken book, and filing it under a folder name would send the
 * admin to look at the wrong thing.
 */
fun ScanError.issuePathOrNull(): String? =
    when (this) {
        is ScanError.FileUnreadable -> path
        is ScanError.MetadataParseError -> path
        is ScanError.TitleInferenceError -> path
        else -> null
    }

/**
 * Brings the issue record in line with what a completed scan just found.
 *
 * Order matters: clears first, then records. A folder that failed and now imports must lose its
 * issue, and doing the clears afterwards could wipe a fresh issue recorded in the same pass for a
 * path that appears in both lists.
 */
suspend fun ScanIssueRepository.reconcile(
    libraryId: LibraryId,
    importedRelPaths: List<String>,
    errors: List<ScanError>,
) {
    importedRelPaths.forEach { clear(libraryId, it) }
    errors.forEach { error ->
        error.issuePathOrNull()?.let { path ->
            record(libraryId, path, error.toIssueReason(), error.debugInfo)
        }
    }
}
