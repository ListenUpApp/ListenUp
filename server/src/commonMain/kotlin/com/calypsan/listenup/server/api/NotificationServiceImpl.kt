package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.NotificationService
import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.notifications.NotificationPrefsRepository
import com.calypsan.listenup.server.sync.NotificationRepository
import kotlin.time.Clock

/**
 * [NotificationService] over the userScoped `notifications` repository — every method scopes to
 * the authenticated caller resolved from [principal]; route handlers call [copyWith] to bind it
 * per-request, and an absent principal is denied (fail closed, the [TagServiceImpl] shape).
 *
 * Mutations route through [NotificationRepository.markRead], the ownership-checked wrapper — the
 * base repository's raw `upsert`/`softDelete` do NOT verify ownership, so this service never
 * touches them with a caller-supplied id.
 */
internal class NotificationServiceImpl(
    private val repo: NotificationRepository,
    private val prefs: NotificationPrefsRepository,
    private val clock: Clock = Clock.System,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : NotificationService {
    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): NotificationServiceImpl =
        NotificationServiceImpl(repo, prefs, clock, principal)

    override suspend fun markRead(notificationId: String): AppResult<Unit> {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.PermissionDenied())
        return repo.markRead(
            notificationId = notificationId,
            userId = caller.userId.value,
            readAtMs = clock.now().toEpochMilliseconds(),
        )
    }

    override suspend fun getPreferences(): AppResult<List<NotificationPreferenceDto>> {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.PermissionDenied())
        return AppResult.Success(prefs.listResolved(caller.userId.value))
    }

    override suspend fun updatePreference(
        type: String,
        preference: NotificationPreference,
    ): AppResult<Unit> {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.PermissionDenied())
        val known = prefs.update(caller.userId.value, type, preference)
        return if (known) {
            AppResult.Success(Unit)
        } else {
            AppResult.Failure(SyncError.NotFound(domain = "notification_prefs", entityId = type))
        }
    }
}
