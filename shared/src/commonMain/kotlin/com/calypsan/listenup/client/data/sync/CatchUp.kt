package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.core.AppResult

/**
 * Engine-facing seam for REST catch-up. Allows [SyncEngine] to be tested with
 * fakes without dragging the full HTTP client. Implemented by [SyncCatchUpClient].
 */
interface CatchUp {
    suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit>

    suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit>

    suspend fun domains(): AppResult<List<String>>
}
