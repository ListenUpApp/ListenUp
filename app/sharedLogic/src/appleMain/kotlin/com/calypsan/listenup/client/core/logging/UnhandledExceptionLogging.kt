package com.calypsan.listenup.client.core.logging

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.usleep
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import kotlin.time.Duration.Companion.seconds

// Long enough for the writer to land a burst; short enough that a crash never feels like a hang.
private val CRASH_FLUSH_TIMEOUT = 1.seconds

/**
 * Installs a Kotlin/Native unhandled-exception hook that logs the throwable at ERROR — to OSLog and,
 * through the tee, the log file — flushes the file, and then calls whatever hook was there before.
 *
 * Termination is unchanged: Kotlin/Native ends the process after the hook returns, exactly as it
 * did without one. What changes is that the reason survives it, in the file Settings → Share logs
 * hands over. Recording is best-effort; nothing in it may stop the previous hook from running.
 */
@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
internal fun installUnhandledExceptionLogging() {
    val logger = KotlinLogging.logger("com.calypsan.listenup.client.core.logging.UnhandledException")
    var previous: ReportUnhandledExceptionHook? = null
    previous =
        setUnhandledExceptionHook { throwable ->
            try {
                logger.error(throwable) { "Uncaught exception — the app is about to stop" }
                LogSinkRegistry.flushBlocking(CRASH_FLUSH_TIMEOUT) { pause ->
                    usleep(pause.inWholeMicroseconds.toUInt())
                }
            } catch (_: Throwable) {
                // Recording the crash is best-effort; passing it on is not.
            }
            previous?.invoke(throwable)
        }
}
