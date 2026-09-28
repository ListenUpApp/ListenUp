@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * The signed-in user's Hardcover connection, as the server reports it.
 *
 * The server owns this state — it holds the tokens and does the waiting during a device sign-in —
 * so there is no Room mirror: the connection is live server state, watched while the settings
 * screen shows.
 */
interface HardcoverRepository {
    /** The connection state: current value first, then every change. Never completes; survives reconnects. */
    fun observeConnection(): Flow<HardcoverConnection>

    /** Starts a device sign-in; see [com.calypsan.listenup.api.HardcoverService.startLink]. */
    suspend fun startLink(): AppResult<HardcoverLinkPrompt>

    /** Disconnects; see [com.calypsan.listenup.api.HardcoverService.disconnect]. */
    suspend fun disconnect(): AppResult<Unit>
}
