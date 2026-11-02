package com.calypsan.listenup.web.dom

/** One `MediaImage` of a lock-screen artwork list. */
internal external interface MediaImage {
    var src: String
    var sizes: String
}

/** The init dictionary `MediaMetadata` takes. */
internal external interface MediaMetadataInit {
    var title: String
    var artist: String
    var album: String
    var artwork: Array<MediaImage>
}

/** The global `MediaMetadata` constructor (Chrome 57, Safari 15, Firefox 82). */
internal external class MediaMetadata(
    init: MediaMetadataInit,
)

/** `MediaPositionState`: seconds, which the browser rejects when the position is past the duration. */
internal external interface MediaPositionState {
    var duration: Double
    var position: Double
    var playbackRate: Double
}

/** The details a `seekto` action handler is called with. */
internal external interface MediaSessionActionDetails {
    val seekTime: Double?
}

/** `navigator.mediaSession`, as much of it as the system media controls binding drives. */
internal external interface MediaSession {
    var metadata: MediaMetadata?

    /** `"none"`, `"paused"` or `"playing"`. */
    var playbackState: String

    fun setActionHandler(
        action: String,
        handler: ((MediaSessionActionDetails) -> Unit)?,
    )

    /** No argument clears the position state; the browser keeps the last one otherwise. */
    fun setPositionState(state: MediaPositionState = definedExternally)
}

/** A new [MediaImage]. */
internal fun mediaImage(
    src: String,
    sizes: String,
): MediaImage =
    optionBag {
        this.src = src
        this.sizes = sizes
    }

/** A new [MediaMetadataInit]. */
internal fun mediaMetadataInit(
    title: String,
    artist: String,
    album: String,
    artwork: Array<MediaImage>,
): MediaMetadataInit =
    optionBag {
        this.title = title
        this.artist = artist
        this.album = album
        this.artwork = artwork
    }

/** A new [MediaPositionState]. */
internal fun mediaPositionState(
    duration: Double,
    position: Double,
    playbackRate: Double,
): MediaPositionState =
    optionBag {
        this.duration = duration
        this.position = position
        this.playbackRate = playbackRate
    }
