package com.calypsan.listenup.client.download

/** One recorded end of an earlier process of the app: when it ended ([at], epoch milliseconds) and the platform's [reason]. */
internal data class ProcessExit(
    val at: Long,
    val reason: Int,
)

/**
 * The exit that ended a transfer started at [startedAt]: the first one after it, since the process that started the
 * transfer is the next one to have died. Null when none was recorded after it. A transfer with no start time is charged
 * to the newest exit, the best there is.
 */
internal fun exitThatEnded(
    exits: List<ProcessExit>,
    startedAt: Long?,
): ProcessExit? =
    if (startedAt == null) {
        exits.maxByOrNull { it.at }
    } else {
        exits.filter { it.at >= startedAt }.minByOrNull { it.at }
    }
