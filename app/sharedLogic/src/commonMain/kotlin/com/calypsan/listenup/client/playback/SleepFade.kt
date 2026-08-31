package com.calypsan.listenup.client.playback

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay

private val logger = KotlinLogging.logger {}

/** How long the sleep-timer fade takes, end to end. */
const val SLEEP_FADE_DURATION_MS = 3_000L

/** How many volume writes that fade is spread across. */
private const val FADE_STEPS = 30

/** The pause is given a beat to land before the volume is put back. See [fadeOutAndPause]. */
private const val RESTORE_DELAY_MS = 100L

/**
 * Ramp [controller] to silence, pause it, then put the volume back.
 *
 * Shared rather than restated per client: this is what a sleep timer firing *sounds* like, and two
 * copies would drift the moment either was tuned. Every caller runs the same ramp for the same
 * three seconds.
 *
 * Restoring the volume is not optional bookkeeping — it is the whole reason the last two lines
 * exist. [PlaybackController.setVolume] writes to a player that outlives this fade, so leaving it
 * at zero would make the *next* book start silent, with a working play button and no sound and
 * nothing on screen to explain it. The short [RESTORE_DELAY_MS] gap lets the pause take effect
 * first, so the restore cannot un-silence the last instant of the fade.
 *
 * On Desktop and Apple [PlaybackController.setVolume] is a documented no-op, so the fade is
 * inaudible there and playback simply stops at the end of it. The behaviour that matters — that it
 * stops, once, at the right time — is the same everywhere; only the softness of the landing differs.
 */
suspend fun fadeOutAndPause(controller: PlaybackController) {
    logger.info { "Starting volume fade out" }

    val stepDelay = SLEEP_FADE_DURATION_MS / FADE_STEPS
    val volumeStep = 1f / FADE_STEPS
    var currentVolume = 1f

    repeat(FADE_STEPS) {
        currentVolume = (currentVolume - volumeStep).coerceAtLeast(0f)
        controller.setVolume(currentVolume)
        delay(stepDelay)
    }

    controller.pause()

    delay(RESTORE_DELAY_MS)
    controller.setVolume(1f)

    logger.info { "Fade complete, playback paused" }
}
