package com.calypsan.listenup.client.presentation.player

/** Where the audio comes from or goes to (§3.1, §4.7); the status slot reads [SourceStatus]. */
sealed interface AudioSource {
    /** Every file is on this device. */
    data object Downloaded : AudioSource

    /** Every file is on this device, and the device is offline. */
    data object DownloadedOffline : AudioSource

    /** Streamed from the server as stored. */
    data object Streaming : AudioSource

    /** A non-local file the server transcodes (it has an hlsUrl). */
    data object StreamingTranscoded : AudioSource

    /** Not downloaded, and the device is offline or the server is unreachable. */
    data object Offline : AudioSource

    /** The browser's wording for "unreachable"; native says [Offline]. */
    data object ServerUnreachable : AudioSource

    /** "Playing on [deviceName]". */
    data class Casting(
        val deviceName: String,
    ) : AudioSource

    /** Held or revoked. */
    data object NotPlayable : AudioSource
}
