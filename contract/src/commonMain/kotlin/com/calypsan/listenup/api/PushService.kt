package com.calypsan.listenup.api

import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.api.result.AppResult
import kotlinx.rpc.annotations.Rpc

/**
 * Device push-token registry + pipeline diagnostics (authed). Tokens bind to
 * the CALLING session and die with it (logout, family revocation, expiry).
 * See the push design spec (2026-07-10).
 */
@Rpc
interface PushService {
    /** Registers (or re-binds, after FCM rotation) this device's push token. Typed failure when push is disabled. */
    suspend fun registerToken(token: String, platform: PushPlatform): AppResult<Unit>

    /** Removes a token — best-effort logout hygiene; session eviction is the backstop. */
    suspend fun unregisterToken(token: String): AppResult<Unit>

    /** Sends a TestNotification to the calling user's own devices. Rate-limited. */
    suspend fun sendTestNotification(): AppResult<Unit>
}
