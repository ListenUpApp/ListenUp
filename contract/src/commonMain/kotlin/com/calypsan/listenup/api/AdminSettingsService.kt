package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.admin.AdminServerSettings
import com.calypsan.listenup.api.dto.admin.AdminServerSettingsPatch
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import kotlinx.rpc.annotations.Rpc

/**
 * Admin-only server-identity settings (server name + remote URL), and Admin → Hardcover: the API
 * token (write-only) and the Hardcover metadata switch. Every method requires ROOT/ADMIN; non-admins
 * receive [com.calypsan.listenup.api.error.AuthError.PermissionDenied].
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

    /** Every outside rating source, with its enabled flag and last-fetch health. */
    suspend fun getRatingSources(): AppResult<List<RatingSourceStatus>>

    /** Switches [source] on or off; disabling flips `enabled` on each of its existing rows. */
    suspend fun setRatingSourceEnabled(
        source: ExternalRatingSource,
        enabled: Boolean,
    ): AppResult<List<RatingSourceStatus>>

    /** Admin → Hardcover: whether an API token is stored and whose it is (never the token), and the metadata switch. */
    suspend fun getHardcoverSource(): AppResult<HardcoverSourceStatus>

    /**
     * Checks [token] with Hardcover and, when Hardcover accepts it, stores it encrypted for every catalogue
     * read. Sent once; never returned. Errors: [com.calypsan.listenup.api.error.HardcoverError.TokenRejected]
     * when Hardcover refuses it, [com.calypsan.listenup.api.error.HardcoverError.Unavailable] when Hardcover
     * can't be reached, [com.calypsan.listenup.api.error.AdminError.InvalidInput] for a blank value. Nothing
     * is stored on any error.
     */
    suspend fun setHardcoverApiToken(token: String): AppResult<HardcoverSourceStatus>

    /** Removes the API token; catalogue reads borrow a connected account again. */
    suspend fun clearHardcoverApiToken(): AppResult<HardcoverSourceStatus>

    /** Switches whether Hardcover fills gaps when a book is matched. */
    suspend fun setHardcoverMetadataEnabled(enabled: Boolean): AppResult<HardcoverSourceStatus>
}
