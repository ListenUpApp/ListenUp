package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.dto.preferences.UserPreferencesDto
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.User_settings
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

private const val DEFAULT_SPEED = 1.0f
private const val DEFAULT_SKIP_FORWARD = 30
private const val DEFAULT_SKIP_BACKWARD = 10
private val SPEED_RANGE = 0.5f..4.0f
private val SKIP_RANGE = 5..300
private val SLEEP_RANGE = 1..480

/**
 * Server-side implementation of [UserPreferencesService].
 *
 * Both operations are scoped to the authenticated principal. `getMyPreferences` returns the
 * caller's stored row or [defaults] when none exists; `updateMyPreferences` does get-or-create →
 * partial-merge (only non-null request fields) → clamp numeric values into range → persist with a
 * fresh `updated_at`.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal; the Koin
 * singleton carries [PrincipalProvider.None] so an un-scoped call is denied rather than running
 * unauthenticated.
 */
internal class UserPreferencesServiceImpl(
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
    private val principal: PrincipalProvider = PrincipalProvider.None,
    private val bus: ChangeBus? = null,
) : UserPreferencesService {
    override suspend fun getMyPreferences(): AppResult<UserPreferencesDto> {
        val userId = currentUserId() ?: return AppResult.Failure(AuthError.PermissionDenied())
        return suspendTransaction(sql) { AppResult.Success(readRow(userId) ?: defaults()) }
    }

    override suspend fun updateMyPreferences(request: UpdateUserPreferencesRequest): AppResult<UserPreferencesDto> {
        val userId = currentUserId() ?: return AppResult.Failure(AuthError.PermissionDenied())
        val merged =
            suspendTransaction(sql) {
                val current = readRow(userId) ?: defaults()
                val next =
                    UserPreferencesDto(
                        defaultPlaybackSpeed =
                            (request.defaultPlaybackSpeed ?: current.defaultPlaybackSpeed).coerceIn(SPEED_RANGE),
                        defaultSkipForwardSec =
                            (request.defaultSkipForwardSec ?: current.defaultSkipForwardSec).coerceIn(SKIP_RANGE),
                        defaultSkipBackwardSec =
                            (request.defaultSkipBackwardSec ?: current.defaultSkipBackwardSec).coerceIn(SKIP_RANGE),
                        defaultSleepTimerMin =
                            (request.defaultSleepTimerMin ?: current.defaultSleepTimerMin)?.coerceIn(SLEEP_RANGE),
                        shakeToResetSleepTimer = request.shakeToResetSleepTimer ?: current.shakeToResetSleepTimer,
                    )
                upsert(userId, next)
                next
            }
        // The write is durable (transaction committed); nudge the user's OTHER devices to re-pull.
        // Per-user targeted via [ChangeBus.publishControl] — the firehose delivers it only to this
        // user's subscribers, never another user's. The echo on the originating device re-fetches the
        // same values and writes through idempotently, so it does not flicker.
        publishPreferencesChanged(userId)
        return AppResult.Success(merged)
    }

    /** Best-effort per-user firehose nudge. A failed publish must not fail the (committed) write. */
    private suspend fun publishPreferencesChanged(userId: String) {
        val bus = bus ?: return
        try {
            bus.publishControl(SyncControl.PreferencesChanged, userId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // The write is already durable; a dropped nudge is recovered on the client's next pull.
        }
    }

    /** Reads the caller's row. Must run inside a SQLDelight transaction. */
    private fun readRow(userId: String): UserPreferencesDto? =
        sql.userSettingsQueries
            .selectByUserId(userId)
            .executeAsOneOrNull()
            ?.toDto()

    /** Get-or-create write: UPDATE first, INSERT when no row matched. Must run inside a transaction. */
    private fun upsert(
        userId: String,
        prefs: UserPreferencesDto,
    ) {
        val now = clock.now().toString()
        sql.userSettingsQueries.update(
            default_playback_speed = prefs.defaultPlaybackSpeed.toDouble(),
            default_skip_forward_sec = prefs.defaultSkipForwardSec.toLong(),
            default_skip_backward_sec = prefs.defaultSkipBackwardSec.toLong(),
            default_sleep_timer_min = prefs.defaultSleepTimerMin?.toLong(),
            shake_to_reset_sleep_timer = prefs.shakeToResetSleepTimer.toDbLong(),
            updated_at = now,
            user_id = userId,
        )
        if (sql.userSettingsQueries.changes().executeAsOne() == 0L) {
            sql.userSettingsQueries.insert(
                user_id = userId,
                default_playback_speed = prefs.defaultPlaybackSpeed.toDouble(),
                default_skip_forward_sec = prefs.defaultSkipForwardSec.toLong(),
                default_skip_backward_sec = prefs.defaultSkipBackwardSec.toLong(),
                default_sleep_timer_min = prefs.defaultSleepTimerMin?.toLong(),
                shake_to_reset_sleep_timer = prefs.shakeToResetSleepTimer.toDbLong(),
                updated_at = now,
            )
        }
    }

    private fun User_settings.toDto(): UserPreferencesDto =
        UserPreferencesDto(
            defaultPlaybackSpeed = default_playback_speed.toFloat(),
            defaultSkipForwardSec = default_skip_forward_sec.toInt(),
            defaultSkipBackwardSec = default_skip_backward_sec.toInt(),
            defaultSleepTimerMin = default_sleep_timer_min?.toInt(),
            shakeToResetSleepTimer = shake_to_reset_sleep_timer != 0L,
        )

    private fun defaults(): UserPreferencesDto =
        UserPreferencesDto(DEFAULT_SPEED, DEFAULT_SKIP_FORWARD, DEFAULT_SKIP_BACKWARD, null, false)

    private fun currentUserId(): String? = principal.current()?.userId?.value

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): UserPreferencesServiceImpl =
        UserPreferencesServiceImpl(sql = sql, clock = clock, principal = principal, bus = bus)
}

/** Boolean → SQLite INTEGER (0/1) at the persistence boundary. */
private fun Boolean.toDbLong(): Long = if (this) 1L else 0L

/**
 * Public factory for tests that need a [UserPreferencesService] without going through Koin.
 *
 * Mirrors [createProfileService] — cross-module test harnesses build the service without piercing
 * the `internal` access on [UserPreferencesServiceImpl]. Production wiring constructs the impl
 * directly inside the Koin module.
 */
fun createUserPreferencesService(
    sql: ListenUpDatabase,
    bus: ChangeBus? = null,
): UserPreferencesService = UserPreferencesServiceImpl(sql = sql, bus = bus)

/**
 * Scopes a [UserPreferencesService] built by [createUserPreferencesService] to [principal] for one
 * request. Mirrors [profileServiceScopedTo]; production wiring calls [UserPreferencesServiceImpl.copyWith]
 * directly in the RPC route.
 */
fun userPreferencesServiceScopedTo(
    service: UserPreferencesService,
    principal: PrincipalProvider,
): UserPreferencesService = (service as UserPreferencesServiceImpl).copyWith(principal)
