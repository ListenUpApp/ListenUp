package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.result.AppResult
import kotlinx.rpc.annotations.Rpc

/**
 * Public instance contract — anonymous callers welcome. Mounted at
 * `/api/rpc/public` (RPC) and under `GET /api/v1/instance` (REST).
 *
 * The client calls [getServerInfo] before authentication to verify a URL points
 * at a ListenUp server and to route onboarding (setup-vs-login, and whether to
 * offer self-registration). Like the rest of the contract, the single method
 * returns [AppResult] so failures cross the wire as typed values, not thrown
 * exceptions.
 */
@Rpc
interface InstanceService {
    /** Returns this server's identity, version, setup state, and registration mode. */
    suspend fun getServerInfo(): AppResult<ServerInfo>
}
