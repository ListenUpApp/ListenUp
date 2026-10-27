package com.calypsan.listenup.client.features.nowplaying

/** How Now Playing arrives and leaves. */
internal enum class EntryMotion {
    /** The phone bar becomes Now Playing along one progress (canvas `MorphAndroid`). */
    Morph,

    /** Now Playing fades in while scaling from 96 %, and out the same way (canvas `Edges`, "wide windows"). */
    FadeThrough,
}

/**
 * Whether Now Playing morphs out of the bar or fades through. The morph runs only from the phone bar ([usesPhoneBar])
 * into the [NowPlayingLayout.Stacked] player, the one geometry the canvas draws. The floating toolbar, the side-by-side
 * and tabletop players fade through, and so does a close that changes the top destination ([destinationChanged]):
 * the bar is about to move, so there is no place to land in. Pure.
 */
internal fun nowPlayingEntryMotion(
    usesPhoneBar: Boolean,
    layout: NowPlayingLayout,
    destinationChanged: Boolean,
): EntryMotion =
    if (usesPhoneBar && layout == NowPlayingLayout.Stacked && !destinationChanged) {
        EntryMotion.Morph
    } else {
        EntryMotion.FadeThrough
    }
