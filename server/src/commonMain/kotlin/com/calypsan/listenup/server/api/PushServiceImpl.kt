@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.PushService
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.PushError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.push.PushConfig
import com.calypsan.listenup.server.push.PushNotifier
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import kotlin.time.Clock

private const val MAX_TOKEN_LENGTH = 4096

/**
 * [PushService] implementation — the session-bound device push-token registry.
 *
 * Resolves the authenticated caller from [principal] (never from request fields). Push is
 * gated by [enabled]: the admin toggle ([ServerSettingsRepository.pushNotificationsEnabled])
 * AND a configured relay ([PushConfig.configured]) must both hold, else `registerToken` /
 * `sendTestNotification` fail with [PushError.PushDisabled]. `unregisterToken` is exempt —
 * best-effort logout hygiene must work even when push is off, so a stale token doesn't
 * outlive the toggle.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal; the
 * Koin singleton carries an unscoped placeholder [PrincipalProvider] that throws (fail-loud)
 * if ever invoked, so a route that forgets to [copyWith] surfaces as a guarded `InternalError`
 * rather than silently leaking unscoped data.
 */
internal class PushServiceImpl(
    private val db: ListenUpDatabase,
    private val pushConfig: PushConfig,
    private val settings: ServerSettingsRepository,
    private val notifier: PushNotifier,
    private val clock: Clock,
    private val principal: PrincipalProvider,
) : PushService {
    override suspend fun registerToken(
        token: String,
        platform: PushPlatform,
    ): AppResult<Unit> {
        val caller = principal.current() ?: return noPrincipal()
        validateToken(token)?.let { return AppResult.Failure(it) }
        if (!enabled()) return AppResult.Failure(PushError.PushDisabled())
        val now = clock.now().toEpochMilliseconds()
        suspendTransaction(db) {
            db.pushTokensQueries.upsert(
                token = token,
                platform = platform.name,
                session_id = caller.sessionId.value,
                user_id = caller.userId.value,
                now = now,
            )
        }
        return AppResult.Success(Unit)
    }

    override suspend fun unregisterToken(token: String): AppResult<Unit> {
        val caller = principal.current() ?: return noPrincipal()
        suspendTransaction(db) {
            db.pushTokensQueries.deleteByTokenForUser(token = token, user_id = caller.userId.value)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun sendTestNotification(): AppResult<Unit> {
        val caller = principal.current() ?: return noPrincipal()
        if (!enabled()) return AppResult.Failure(PushError.PushDisabled())
        notifier.notify(caller.userId.value, PushPayload.TestNotification(sentAtMs = clock.now().toEpochMilliseconds()))
        return AppResult.Success(Unit)
    }

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): PushServiceImpl =
        PushServiceImpl(
            db = db,
            pushConfig = pushConfig,
            settings = settings,
            notifier = notifier,
            clock = clock,
            principal = principal,
        )

    private suspend fun enabled(): Boolean = settings.pushNotificationsEnabled() && pushConfig.configured

    private fun validateToken(token: String): ValidationError? =
        when {
            token.isBlank() -> ValidationError(message = "token must not be blank.")
            token.length > MAX_TOKEN_LENGTH -> ValidationError(message = "token is too long.")
            else -> null
        }

    private fun noPrincipal(): AppResult.Failure = AppResult.Failure(AuthError.PermissionDenied())
}
