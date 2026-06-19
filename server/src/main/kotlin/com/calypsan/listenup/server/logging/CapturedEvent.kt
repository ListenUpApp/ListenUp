package com.calypsan.listenup.server.logging

import org.slf4j.event.Level

/**
 * A log event captured in-memory for test assertions.
 *
 * Installed via [ListenUpLoggerFactory.installTestCapture] / [ListenUpLoggerFactory.removeTestCapture].
 */
data class CapturedEvent(
    val level: Level,
    val loggerName: String,
    val message: String,
    val mdc: Map<String, String>,
    val throwable: Throwable?,
)
