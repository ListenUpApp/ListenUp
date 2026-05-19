package com.calypsan.listenup.api

import com.calypsan.listenup.api.result.AppResult
import kotlinx.rpc.annotations.Rpc

/**
 * Phase 0 smoke-test service.
 *
 * Exists only to prove the kotlinx.rpc pipeline (codegen → server registration →
 * client proxy → wire round-trip) works on CIO. Will be deleted in Phase 1 when
 * real domain @Rpc services replace it.
 */
@Rpc
interface PingService {
    /** Returns a constant string. Proves the full RPC round-trip is operational. */
    suspend fun ping(): AppResult<String>
}
