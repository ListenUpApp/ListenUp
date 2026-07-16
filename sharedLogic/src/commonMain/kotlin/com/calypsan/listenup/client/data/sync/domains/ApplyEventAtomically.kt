package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.api.result.AppResult
import io.github.oshai.kotlinlogging.KLogger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext

/**
 * Runs [block] inside one IMMEDIATE write transaction, mapping any escaped
 * failure to a typed [SyncError.SyncFailed]. [CancellationException] is
 * re-thrown — cancellation is not a sync failure.
 *
 * The canonical apply-wrapper for [ComposedSyncDomainHandler] — every domain's
 * `onEvent` / `onCatchUpItem` work routes through it.
 *
 * A [PostCommitSideEffects] collector is installed for the duration of [block] so an apply
 * can defer file-system/network side effects (via [deferUntilCommit]) past commit. The
 * collected actions run only when the transaction succeeds; a rollback discards them. Those
 * actions are inherently best-effort (see [deferUntilCommit]'s callers) — a failure there must
 * never turn an already-committed apply into a reported [AppResult.Failure], so [runAll] is
 * outside the try/catch that maps [block] failures to [SyncError.SyncFailed].
 */
internal suspend fun TransactionRunner.applyEventAtomically(
    domain: String,
    entityId: String,
    log: KLogger,
    block: suspend () -> Unit,
): AppResult<Unit> {
    val sideEffects = PostCommitSideEffects()
    try {
        withContext(sideEffects) { atomically { block() } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "Failed to apply $domain sync event for $entityId" }
        return AppResult.Failure(SyncError.SyncFailed(debugInfo = "$domain/$entityId: ${e.message}"))
    }

    try {
        sideEffects.runAll()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Best-effort by contract (see PostCommitSideEffects KDoc): the transaction already
        // committed, so a deferred side effect failing here must not be reported as a failed apply.
        log.warn(e) { "Post-commit side effect failed for $domain sync event $entityId (apply still succeeded)" }
    }
    return AppResult.Success(Unit)
}
