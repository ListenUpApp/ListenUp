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
 * collected actions run only when the transaction succeeds; a rollback discards them.
 */
internal suspend fun TransactionRunner.applyEventAtomically(
    domain: String,
    entityId: String,
    log: KLogger,
    block: suspend () -> Unit,
): AppResult<Unit> {
    val sideEffects = PostCommitSideEffects()
    return try {
        withContext(sideEffects) { atomically { block() } }
        sideEffects.runAll()
        AppResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "Failed to apply $domain sync event for $entityId" }
        AppResult.Failure(SyncError.SyncFailed(debugInfo = "$domain/$entityId: ${e.message}"))
    }
}
