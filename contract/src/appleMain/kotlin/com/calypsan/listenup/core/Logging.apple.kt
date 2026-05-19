package com.calypsan.listenup.core

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Configures kotlin-logging for Apple platforms (iOS/macOS).
 *
 * On Apple platforms, kotlin-logging outputs to the console by default,
 * which is captured by Xcode's debug console or Console.app.
 *
 * Call this once during app initialization to log a startup message.
 */
fun configureLogging() {
    logger.info { "ListenUp Apple logging initialized" }
}
