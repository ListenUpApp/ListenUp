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
 * **Two distinct actions, because they are two distinct things.** [saveSettings] records the rules
 * — live for future arrivals immediately, and not one file moves. [saveAndExecute] is the explicit
 * "Organize Library" sweep: [getSettings] → the admin picks a schema → [preview] renders the
 * consent dialog (full scope + before→after rows) → [saveAndExecute] persists AND relocates every
 * non-conforming book → [observeRun] streams progress to a terminal [OrganizeRunEvent.Completed].
 *
 * Settings persist in the server's `server_settings` key/value store. There is no on/off switch:
 * the rules always exist, and what they apply to is decided by a book's origin — uploads conform,
 * scan-discovered books stay where they were put, and an edited book relocates only when it was
 * already at its canonical path.
 */
@Rpc
interface OrganizeService {
    /** The persisted organizer settings, or defaults when never configured. */
    suspend fun getSettings(): AppResult<OrganizeSettingsDto>

    /**
     * Persists [settings] and starts nothing — the quiet Save. The rules take effect for future
     * arrivals immediately; not one file moves.
     *
     * Deliberately does NOT probe the library roots for writability the way [saveAndExecute] does:
     * recording rules touches no files, so an unwritable root is no reason to refuse. Never
     * Stranded — an admin whose disk is unreachable can still say what they want to happen.
     */
    suspend fun saveSettings(settings: OrganizeSettingsDto): AppResult<Unit>

    /**
     * Plans a full-library reorganization under [settings] WITHOUT touching disk or persisting
     * anything — the consent-dialog data. Books already at their canonical path are excluded.
     */
    suspend fun preview(settings: OrganizeSettingsDto): AppResult<OrganizePreviewDto>

    /**
     * Persists [settings] and immediately starts the full-library reorganization, returning the
     * run's id for [observeRun] — the explicit "Organize Library" sweep, and the one place bulk
     * relocation happens. Fails typed (settings NOT persisted) when a library folder root isn't
     * writable ([com.calypsan.listenup.api.error.LibraryWriteError.Unavailable]) or another run is
     * still in flight. To record rules without moving anything, call [saveSettings] instead.
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
