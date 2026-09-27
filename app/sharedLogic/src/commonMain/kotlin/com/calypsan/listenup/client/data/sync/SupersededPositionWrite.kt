package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.dto.RecordPositionResult

/**
 * The log line for a position write the server kept its own row over, or null when the write landed.
 *
 * Positions are `lastPlayedAt`-wins on the server, and `lastPlayedAt` is this device's wall clock, so a
 * device whose clock runs behind loses writes it made later in real time — a finish among them. The
 * line carries the measured clock offset (server clock minus [deviceNowMs]) so the logs can show
 * whether skew explains a lost write before anyone builds causal ordering to fix it.
 */
internal fun describeSupersededPositionWrite(
    request: RecordPositionRequest,
    result: RecordPositionResult,
    deviceNowMs: Long,
): String? {
    if (result.accepted) return null
    return "Position write superseded for book=${request.bookId}: " +
        "sent lastPlayedAt=${request.lastPlayedAt} pos=${request.positionMs} finished=${request.finished}; " +
        "server kept lastPlayedAt=${result.position.lastPlayedAt} pos=${result.position.positionMs}; " +
        "deviceClockOffsetMs=${result.serverNowMs - deviceNowMs}"
}
