@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.organize.OrganizePreviewDto
import com.calypsan.listenup.api.dto.organize.OrganizeRunEvent
import com.calypsan.listenup.api.dto.organize.OrganizeRunId
import com.calypsan.listenup.api.dto.organize.OrganizeSettingsDto
import com.calypsan.listenup.api.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for the admin file-organizer domain (#850).
 *
 * Suspend methods return [AppResult] so callers exhaustively fold over typed error values.
 * [observeRun] is a cold server-pushed stream of run progress ending after the terminal
 * [OrganizeRunEvent.Completed].
 *
 * Implementations back this contract with the [com.calypsan.listenup.api.OrganizeService]
 * RPC proxy via [com.calypsan.listenup.client.data.remote.OrganizeRpcFactory].
 */
interface OrganizeRepository {
    /** The persisted organizer settings, or defaults when never configured. */
    suspend fun getSettings(): AppResult<OrganizeSettingsDto>

    /** Plans a full-library reorganization under [settings] without persisting or moving anything. */
    suspend fun preview(settings: OrganizeSettingsDto): AppResult<OrganizePreviewDto>

    /** Persists [settings] and (when enabled) starts the full-library run, returning its id. */
    suspend fun saveAndExecute(settings: OrganizeSettingsDto): AppResult<OrganizeRunId>

    /** Streams [runId]'s progress events, replayed from the start, ending after the terminal event. */
    fun observeRun(runId: OrganizeRunId): Flow<OrganizeRunEvent>

    /** The in-flight run's id for re-attachment, or null when idle. */
    suspend fun resumeRun(): AppResult<OrganizeRunId?>
}
