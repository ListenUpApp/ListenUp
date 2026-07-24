package com.calypsan.listenup.client.playback

/**
 * A snapshot of one audio file's metadata, shaped for diagnostic logging around playback
 * preparation and downloads.
 *
 * Despite the name, this is **not** a wire DTO — it is built from a Room [AudioFileEntity][
 * com.calypsan.listenup.client.data.local.db.AudioFileEntity], never parsed off the network, and
 * lives in `playback/` rather than `data/remote/model/` for exactly that reason.
 */
internal data class AudioFileResponse(
    val id: String,
    val filename: String,
    val format: String,
    val codec: String = "",
    val duration: Long,
    val size: Long,
)
