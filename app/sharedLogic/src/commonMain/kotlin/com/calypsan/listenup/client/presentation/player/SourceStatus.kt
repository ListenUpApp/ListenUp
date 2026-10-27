package com.calypsan.listenup.client.presentation.player

/**
 * What Now Playing's status slot says (Simon, 2026-10-27): only news. Downloaded, streaming, downloaded-while-offline
 * and casting say nothing (the Cast and AirPlay controls name the device). Each platform words the four cases.
 */
sealed interface SourceStatus {
    /** Not downloaded, and the device or the server can't be reached (native wording). */
    data object Offline : SourceStatus

    /** The browser's wording for unreachable: "Can't reach your server". */
    data object ServerUnreachable : SourceStatus

    /** Streamed through the server's transcoder. */
    data object StreamingTranscoded : SourceStatus

    /** Held or revoked. */
    data object NotPlayable : SourceStatus
}

/** [source]'s status, or null when it has no news. Pure. */
internal fun sourceStatusOf(source: AudioSource): SourceStatus? =
    when (source) {
        AudioSource.Downloaded, AudioSource.DownloadedOffline, AudioSource.Streaming, is AudioSource.Casting -> null
        AudioSource.Offline -> SourceStatus.Offline
        AudioSource.ServerUnreachable -> SourceStatus.ServerUnreachable
        AudioSource.StreamingTranscoded -> SourceStatus.StreamingTranscoded
        AudioSource.NotPlayable -> SourceStatus.NotPlayable
    }
