package com.calypsan.listenup.client.playback

/**
 * What a [PlayerEngine.load] that handed the book to the engine learned from preparing it, beyond what the engine
 * itself reports.
 */
data class EngineLoad(
    val serverConversion: ServerConversion,
)

/**
 * Whether the server could convert every file this device said it can't play, as the book's prepare reported.
 *
 * A hint, never a failure on its own: a device declares its codecs conservatively, so a file flagged here may still
 * play. It only explains a decode failure that actually happened.
 */
enum class ServerConversion {
    /** Nothing was left unconverted: no file needed it, the server converts the ones that did, or none was asked. */
    COVERED,

    /** At least one file needs converting for this device and the server can't, so it sent the original. */
    UNAVAILABLE,
}
