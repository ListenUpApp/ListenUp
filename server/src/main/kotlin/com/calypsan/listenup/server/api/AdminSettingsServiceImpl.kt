package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.dto.admin.AdminServerSettings
import com.calypsan.listenup.api.dto.admin.AdminServerSettingsPatch
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AdminError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.settings.ServerSettingsRepository

/** Max length for the operator-set server name. */
private const val MAX_SERVER_NAME = 100

/** Max length for the operator-set remote URL. */
private const val MAX_REMOTE_URL = 2048

/**
 * [AdminSettingsService] implementation — server-identity settings (server name, remote URL)
 * backed by [ServerSettingsRepository]. Admin-gated via [requireAdmin]; route handlers bind
 * the caller via [copyWith] (the Koin singleton carries an unscoped placeholder).
 */
class AdminSettingsServiceImpl(
    private val settings: ServerSettingsRepository,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : AdminSettingsService {
    /** Returns a copy scoped to the given [provider]. Route handlers call this per-request. */
    fun copyWith(provider: PrincipalProvider): AdminSettingsServiceImpl = AdminSettingsServiceImpl(settings, provider)

    override suspend fun getServerSettings(): AppResult<AdminServerSettings> {
        requireAdmin()?.let { return it }
        return AppResult.Success(current())
    }

    override suspend fun updateServerSettings(patch: AdminServerSettingsPatch): AppResult<AdminServerSettings> {
        requireAdmin()?.let { return it }
        patch.serverName?.let { name ->
            val trimmed = name.trim()
            if (trimmed.isBlank() || trimmed.length > MAX_SERVER_NAME) {
                return AppResult.Failure(AdminError.InvalidInput())
            }
            settings.setServerName(trimmed)
        }
        patch.remoteUrl?.let { url ->
            if (url.length > MAX_REMOTE_URL) return AppResult.Failure(AdminError.InvalidInput())
            settings.setRemoteUrl(url)
        }
        return AppResult.Success(current())
    }

    private suspend fun current(): AdminServerSettings =
        AdminServerSettings(serverName = settings.serverName(), remoteUrl = settings.remoteUrl())

    /** null = allowed; a Failure (PermissionDenied / SessionExpired) otherwise. */
    private fun requireAdmin(): AppResult.Failure? {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        return if (caller.role.isAdmin()) null else AppResult.Failure(AuthError.PermissionDenied())
    }

    private fun UserRole.isAdmin(): Boolean = this == UserRole.ROOT || this == UserRole.ADMIN
}
