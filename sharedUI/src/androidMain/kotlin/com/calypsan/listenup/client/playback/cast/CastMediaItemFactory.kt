package com.calypsan.listenup.client.playback.cast

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Title/artist/album of a currently-loaded queue item, keyed by [fileId] (= MediaItem.mediaId). */
data class CastSourceItem(
    val fileId: String,
    val title: String?,
    val artist: String?,
    val albumTitle: String?,
)

/** A file from a fresh `/playback/prepare` call: absolute network URL + format token. */
data class CastPreparedFile(
    val fileId: String,
    val absoluteUrl: String,
    val format: String,
)

/** One resolved cast track (pure data — assembled into a Media3 [MediaItem] by [CastMediaItemFactory.toMediaItem]). */
data class CastTrack(
    val fileId: String,
    val uri: String,
    val mimeType: String,
    val title: String?,
    val artist: String?,
    val albumTitle: String?,
    val artworkUri: String?,
)

/** Result of a build: the castable tracks in queue order, plus how many files were dropped as un-castable. */
data class CastTracks(
    val tracks: List<CastTrack>,
    val droppedUncastable: Int,
)

/**
 * Builds the Cast queue. Zips the currently-loaded items (stable order +
 * on-TV metadata) with a fresh `/playback/prepare` result (network URLs +
 * formats), matched by file id. Files whose format Cast can't decode are
 * dropped and counted so the caller can warn/fallback. The on-TV artwork is the
 * signed network [coverUrlAbsolute] — a Cast device can't read the phone's
 * local `file://` cover.
 */
class CastMediaItemFactory {
    fun build(
        currentItems: List<CastSourceItem>,
        prepared: List<CastPreparedFile>,
        coverUrlAbsolute: String?,
    ): CastTracks {
        val preparedById = prepared.associateBy { it.fileId }
        var dropped = 0
        val tracks =
            currentItems.mapNotNull { item ->
                val file = preparedById[item.fileId] ?: return@mapNotNull null
                val mime = castMimeType(file.format)
                if (mime == null) {
                    dropped++
                    return@mapNotNull null
                }
                CastTrack(
                    fileId = item.fileId,
                    uri = file.absoluteUrl,
                    mimeType = mime,
                    title = item.title,
                    artist = item.artist,
                    albumTitle = item.albumTitle,
                    artworkUri = coverUrlAbsolute,
                )
            }
        return CastTracks(tracks, dropped)
    }

    /** Assembles a [CastTrack] into a Media3 [MediaItem] with an explicit MIME type (CastPlayer requires it). */
    fun toMediaItem(track: CastTrack): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.fileId)
            .setUri(track.uri)
            .setMimeType(track.mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.albumTitle)
                    .setArtworkUri(track.artworkUri?.let { Uri.parse(it) })
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .build(),
            )
            .build()
}
