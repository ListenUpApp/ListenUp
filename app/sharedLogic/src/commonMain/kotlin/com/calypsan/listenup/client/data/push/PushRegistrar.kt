package com.calypsan.listenup.client.data.push

import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.PushRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Orchestrates push-token registration against the user's server: post-auth,
 * on platform token rotation, and whenever `ServerInfo.pushEnabled` changes
 * (the admin toggle flips → `ServerInfoChanged` → forced refetch → this runs
 * again). Best-effort — failures log and defer to the next trigger (Never
 * Stranded: push is an accelerant, SSE carries every event regardless).
 *
 * [tokenProvider] is nullable: absence means this build has no platform push
 * hook (desktop, or an Android build without Play services) — every method is
 * then a silent no-op. We never unregister on toggle-disable: the server just
 * stops sending, and the token dies with the session (logout/session eviction
 * is the existing cleanup path).
 */
class PushRegistrar internal constructor(
    private val instanceRepository: InstanceRepository,
    private val pushRepository: PushRepository,
    private val tokenProvider: PushTokenProvider?,
    /**
     * Nullable so this stays no-op-safe on a platform that binds no [PushAvailability] (mirrors
     * [tokenProvider]'s own nullable contract): absence means "can't confirm delivery", not
     * "assume it works" — [registerRegistrationWatch] treats a missing seam the same as a `false`
     * verdict.
     */
    private val availability: PushAvailability? = null,
) {
    /**
     * Registers this device's current push token with the server, if push is
     * enabled there. Call after authentication and after any forced
     * `ServerInfo` refetch.
     */
    suspend fun syncRegistration() {
        val provider = tokenProvider ?: return
        val info = instanceRepository.getServerInfoOrNull() ?: return
        if (!info.pushEnabled) return
        val token = provider.currentToken() ?: return
        pushRepository
            .registerToken(token)
            .onFailure { logger.warn { "push token registration failed: ${it.code}" } }
    }

    /**
     * Registers this device as a pre-auth **registration watch** for the pending registration
     * [userId] (#1068), so an admin decision reaches the device while the app is backgrounded.
     * Returns `true` when a watch was actually registered — the caller may then honestly
     * promise "we'll notify you"; `false` (no provider, push disabled, no token yet, notification
     * delivery not available at the OS level, transport failure) means the status stream/poll is
     * the only channel, and no promise is shown. A token existing is NOT enough on its own — e.g.
     * Android's FCM returns a token even when `POST_NOTIFICATIONS` was never granted — so
     * [availability] gets the final say before the promise is earned. Safe to call repeatedly —
     * the server upserts.
     */
    suspend fun registerRegistrationWatch(userId: String): Boolean {
        val provider = tokenProvider ?: return false
        val info = instanceRepository.getServerInfoOrNull() ?: return false
        if (!info.pushEnabled) return false
        val token = provider.currentToken() ?: return false
        if (availability?.canDeliver() != true) return false
        return when (val result = pushRepository.registerRegistrationWatchToken(userId, token)) {
            is com.calypsan.listenup.api.result.AppResult.Success -> {
                true
            }

            is com.calypsan.listenup.api.result.AppResult.Failure -> {
                logger.warn { "registration watch-token registration failed: ${result.error.code}" }
                false
            }
        }
    }

    /**
     * Re-registers [newToken] after the platform SDK rotates it (e.g. FCM's
     * `onNewToken`). No-ops if the server has push disabled.
     */
    suspend fun onTokenRotated(newToken: String) {
        val info = instanceRepository.getServerInfoOrNull() ?: return
        if (!info.pushEnabled) return
        pushRepository
            .registerToken(newToken)
            .onFailure { logger.warn { "push token rotation registration failed: ${it.code}" } }
    }
}
