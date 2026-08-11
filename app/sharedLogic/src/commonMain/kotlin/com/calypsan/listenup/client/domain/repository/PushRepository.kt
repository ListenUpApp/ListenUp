@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult

/**
 * Device push-token registration against the server's
 * [com.calypsan.listenup.api.PushService] (authed RPC).
 *
 * Tokens bind to the calling session and die with it (logout, family revocation,
 * expiry) — there is no client-side persistence beyond the platform's own token
 * store. Every fallible call folds a typed [AppResult]; there is no `getOrThrow`
 * bridge.
 *
 * Part of the domain layer — implementations live in the data layer.
 */
interface PushRepository {
    /**
     * Registers (or re-binds, after platform token rotation) this device's push token.
     *
     * @param token The platform push token (FCM registration token on Android).
     * @return [AppResult.Success] once bound, or [AppResult.Failure] — typed
     *   `PushError.PushDisabled` when push is switched off on the server.
     */
    suspend fun registerToken(token: String): AppResult<Unit>

    /**
     * Removes a previously registered push token — best-effort logout hygiene;
     * session eviction is the backstop if this call never fires.
     *
     * @param token The platform push token to remove.
     */
    suspend fun unregisterToken(token: String): AppResult<Unit>

    /**
     * Sends a test notification to the calling user's own registered devices.
     * Rate-limited server-side.
     */
    suspend fun sendTestNotification(): AppResult<Unit>
}
