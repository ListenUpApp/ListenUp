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
class PushRegistrar(
    private val instanceRepository: InstanceRepository,
    private val pushRepository: PushRepository,
    private val tokenProvider: PushTokenProvider?,
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
