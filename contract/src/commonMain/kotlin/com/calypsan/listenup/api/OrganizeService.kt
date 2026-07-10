package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.organize.OrganizePreviewDto
import com.calypsan.listenup.api.dto.organize.OrganizeRunEvent
import com.calypsan.listenup.api.dto.organize.OrganizeRunId
import com.calypsan.listenup.api.dto.organize.OrganizeSettingsDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * Admin-only file & folder organization (#850). Every method requires ROOT/ADMIN; non-admins
 * receive [com.calypsan.listenup.api.error.AuthError.PermissionDenied]. Mounted at
 * `/api/rpc/authed` behind the JWT gate; first-party admin UI only (no REST mirror, matching
 * [AdminSettingsService]).
 *
 * The flow the admin UI drives: [getSettings] → user picks a schema → [preview] renders the
 * consent dialog (full scope + before→after rows) → **Save = [saveAndExecute]** (persists the
 * settings AND immediately runs the full-library reorganization; cancel costs nothing) →
 * [observeRun] streams progress to a terminal [OrganizeRunEvent.Completed].
 *
 * Settings persist in the server's `server_settings` key/value store. Disable = stop: turning
 * [OrganizeSettingsDto.enabled] off stops future organization; nothing is ever un-organized.
 */
@Rpc
interface OrganizeService {
    /** The persisted organizer settings, or defaults when never configured. */
    suspend fun getSettings(): AppResult<OrganizeSettingsDto>

    /**
     * Plans a full-library reorganization under [settings] WITHOUT touching disk or persisting
     * anything — the consent-dialog data. Books already at their canonical path are excluded.
     */
    suspend fun preview(settings: OrganizeSettingsDto): AppResult<OrganizePreviewDto>

    /**
     * Persists [settings] and — when [OrganizeSettingsDto.enabled] — immediately starts the
     * full-library reorganization, returning the run's id for [observeRun]. Fails typed (settings
     * NOT persisted with `enabled=true`) when a library folder root isn't writable
     * ([com.calypsan.listenup.api.error.LibraryWriteError.Unavailable]) or another run is still
     * in flight. Saving with `enabled=false` persists the settings and starts nothing.
     */
    suspend fun saveAndExecute(settings: OrganizeSettingsDto): AppResult<OrganizeRunId>

    /**
     * Streams [runId]'s progress events, replaying from the start of the run so a late subscriber
     * still sees the whole story. Completes after the terminal [OrganizeRunEvent.Completed].
     * An unknown/stale [runId] completes immediately without emitting.
     */
    fun observeRun(runId: OrganizeRunId): Flow<RpcEvent<OrganizeRunEvent>>

    /**
     * The id of the currently in-flight run, or `null` when none is active — lets an admin UI
     * re-attach its progress view after a reconnect. Interrupted file moves themselves are
     * recovered by the write journal at boot; a partial-failure "resume" is a fresh
     * [saveAndExecute], which re-plans against current reality (already-moved books no-op).
     */
    suspend fun resumeRun(): AppResult<OrganizeRunId?>
}
