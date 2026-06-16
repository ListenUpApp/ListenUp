package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.dto.preferences.UserPreferencesDto
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.UserSettingsTable
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

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
 * fresh `updated_at`. Mirrors the legacy Go preferences endpoint.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal; the Koin
 * singleton carries [PrincipalProvider.None] so an un-scoped call is denied rather than running
 * unauthenticated.
 */
internal class UserPreferencesServiceImpl(
    private val db: Database,
    private val clock: Clock = Clock.System,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : UserPreferencesService {
    override suspend fun getMyPreferences(): AppResult<UserPreferencesDto> {
        val userId = currentUserId() ?: return AppResult.Failure(AuthError.PermissionDenied())
        return suspendTransaction(db) { AppResult.Success(readRow(userId) ?: defaults()) }
    }

    override suspend fun updateMyPreferences(request: UpdateUserPreferencesRequest): AppResult<UserPreferencesDto> {
        val userId = currentUserId() ?: return AppResult.Failure(AuthError.PermissionDenied())
        return suspendTransaction(db) {
            val current = readRow(userId) ?: defaults()
            val merged =
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
            upsert(userId, merged)
            AppResult.Success(merged)
        }
    }

    private fun readRow(userId: String): UserPreferencesDto? =
        UserSettingsTable
            .selectAll()
            .where { UserSettingsTable.userId eq userId }
            .singleOrNull()
            ?.toDto()

    private fun upsert(
        userId: String,
        prefs: UserPreferencesDto,
    ) {
        val now = clock.now().toString()
        val updated = UserSettingsTable.update({ UserSettingsTable.userId eq userId }) { it.applyPrefs(prefs, now) }
        if (updated == 0) {
            UserSettingsTable.insert {
                it[UserSettingsTable.userId] = userId
                it.applyPrefs(prefs, now)
            }
        }
    }

    private fun UpdateBuilder<*>.applyPrefs(
        prefs: UserPreferencesDto,
        now: String,
    ) {
        this[UserSettingsTable.defaultPlaybackSpeed] = prefs.defaultPlaybackSpeed
        this[UserSettingsTable.defaultSkipForwardSec] = prefs.defaultSkipForwardSec
        this[UserSettingsTable.defaultSkipBackwardSec] = prefs.defaultSkipBackwardSec
        this[UserSettingsTable.defaultSleepTimerMin] = prefs.defaultSleepTimerMin
        this[UserSettingsTable.shakeToResetSleepTimer] = prefs.shakeToResetSleepTimer
        this[UserSettingsTable.updatedAt] = now
    }

    private fun ResultRow.toDto(): UserPreferencesDto =
        UserPreferencesDto(
            defaultPlaybackSpeed = this[UserSettingsTable.defaultPlaybackSpeed],
            defaultSkipForwardSec = this[UserSettingsTable.defaultSkipForwardSec],
            defaultSkipBackwardSec = this[UserSettingsTable.defaultSkipBackwardSec],
            defaultSleepTimerMin = this[UserSettingsTable.defaultSleepTimerMin],
            shakeToResetSleepTimer = this[UserSettingsTable.shakeToResetSleepTimer],
        )

    private fun defaults(): UserPreferencesDto =
        UserPreferencesDto(DEFAULT_SPEED, DEFAULT_SKIP_FORWARD, DEFAULT_SKIP_BACKWARD, null, false)

    private fun currentUserId(): String? = principal.current()?.userId?.value

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): UserPreferencesServiceImpl =
        UserPreferencesServiceImpl(db = db, clock = clock, principal = principal)
}

/**
 * Public factory for tests that need a [UserPreferencesService] without going through Koin.
 *
 * Mirrors [createProfileService] — cross-module test harnesses build the service without piercing
 * the `internal` access on [UserPreferencesServiceImpl]. Production wiring constructs the impl
 * directly inside the Koin module.
 */
fun createUserPreferencesService(db: Database): UserPreferencesService = UserPreferencesServiceImpl(db = db)

/**
 * Scopes a [UserPreferencesService] built by [createUserPreferencesService] to [principal] for one
 * request. Mirrors [profileServiceScopedTo]; production wiring calls [UserPreferencesServiceImpl.copyWith]
 * directly in the RPC route.
 */
fun userPreferencesServiceScopedTo(
    service: UserPreferencesService,
    principal: PrincipalProvider,
): UserPreferencesService = (service as UserPreferencesServiceImpl).copyWith(principal)
