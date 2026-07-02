package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.api.result.AppResult
import io.github.oshai.kotlinlogging.KLogger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block] inside one IMMEDIATE write transaction, mapping any escaped
 * failure to a typed [SyncError.SyncFailed]. [CancellationException] is
 * re-thrown — cancellation is not a sync failure.
 *
 * The canonical apply-wrapper for every [com.calypsan.listenup.client.data.sync.SyncDomainHandler]:
 * `ComposedSyncDomainHandler` and the remaining hand-written handlers
 * all route their `onEvent` / `onCatchUpItem` work through it.
 */
internal suspend fun TransactionRunner.applyEventAtomically(
    domain: String,
    entityId: String,
    log: KLogger,
    block: suspend () -> Unit,
): AppResult<Unit> =
    try {
        atomically { block() }
        AppResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "Failed to apply $domain sync event for $entityId" }
        AppResult.Failure(SyncError.SyncFailed(debugInfo = "$domain/$entityId: ${e.message}"))
    }
