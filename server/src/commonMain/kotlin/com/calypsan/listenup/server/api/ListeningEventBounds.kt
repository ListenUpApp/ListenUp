package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.error.ValidationError

/** One uninterrupted play-to-pause span is hours, never days; a longer one is a forged or corrupt clock. */
internal const val MAX_SPAN_MS: Long = 24L * 60 * 60 * 1000

/** Tolerates a badly set device clock without dead-lettering honest listening, yet stops future-dated streaks. */
internal const val MAX_FUTURE_SKEW_MS: Long = 24L * 60 * 60 * 1000

/** The player offers 0.5x to 3.0x; the extra headroom absorbs future speed options and rounding. */
internal const val MAX_PLAYBACK_SPEED: Float = 4f

/** IANA zone ids are far shorter; this only stops a free-text field from becoming storage. */
internal const val MAX_TZ_LENGTH: Int = 64

/** A device name is a short label; this only stops a free-text field from becoming storage. */
internal const val MAX_DEVICE_LABEL_LENGTH: Int = 200

/**
 * The first rule this listening span breaks, or null when it is plausible. Listening events feed
 * every user's stats, the leaderboard and the activity feed, so the server stores only spans an
 * honest player could have produced.
 */
internal fun RecordListeningEventRequest.boundsViolation(nowMs: Long): ValidationError? =
    when {
        endedAt < startedAt || endedAt - startedAt > MAX_SPAN_MS ||
            endedAt > nowMs + MAX_FUTURE_SKEW_MS ->
            invalid("This listening session's times aren't valid.", "endedAt")
        startedAt < 0L -> invalid("This listening session's start time isn't valid.", "startedAt")
        startPositionMs < 0L -> invalid("This listening session's start position isn't valid.", "startPositionMs")
        endPositionMs < 0L -> invalid("This listening session's end position isn't valid.", "endPositionMs")
        playbackSpeed.isNaN() || playbackSpeed <= 0f || playbackSpeed > MAX_PLAYBACK_SPEED ->
            invalid("This listening session's playback speed isn't valid.", "playbackSpeed")
        tz.length > MAX_TZ_LENGTH -> invalid("This listening session's timezone isn't valid.", "tz")
        (deviceLabel?.length ?: 0) > MAX_DEVICE_LABEL_LENGTH ->
            invalid("This listening session's device name is too long.", "deviceLabel")
        else -> null
    }

private fun invalid(
    message: String,
    field: String,
): ValidationError = ValidationError(message = message, field = field)
