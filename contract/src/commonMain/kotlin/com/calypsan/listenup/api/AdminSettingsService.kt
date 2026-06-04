package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.admin.AdminServerSettings
import com.calypsan.listenup.api.dto.admin.AdminServerSettingsPatch
import com.calypsan.listenup.api.result.AppResult
import kotlinx.rpc.annotations.Rpc

/**
 * Admin-only server-identity settings (server name + remote URL). Every method requires
 * ROOT/ADMIN; non-admins receive [com.calypsan.listenup.api.error.AuthError.PermissionDenied].
 *
 * Persisted server-side in the `server_settings` key/value store. [getServerSettings]
 * reads the current values (server name falls back to the build default when unset);
 * [updateServerSettings] applies a partial patch and returns the new state.
 */
@Rpc
interface AdminSettingsService {
    /** Returns the current server-identity settings. */
    suspend fun getServerSettings(): AppResult<AdminServerSettings>

    /** Applies [patch] (null fields unchanged; `remoteUrl=""` clears) and returns the new settings. */
    suspend fun updateServerSettings(patch: AdminServerSettingsPatch): AppResult<AdminServerSettings>
}
