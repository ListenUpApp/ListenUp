package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.PlaybackStartGate

/**
 * The browser's [PlaybackStartGate]. [prime] spends the user's tap on the `<audio>` element inside the click
 * task (iOS Safari's activation is transient, and the prepare is an RPC away); [withdraw] takes that standing
 * "play the next thing you load" back when the prepare fails or is superseded, so a later load cannot start on
 * the strength of a tap that went nowhere; [release] lets go of hls.js and the media entirely on Close book.
 * This is the logic the web player's own `playBook` / `closeBook` once held; the supersede guard is `PlayerSession`'s.
 */
internal class HtmlAudioStartGate(
    private val player: HtmlAudioPlayer,
) : PlaybackStartGate {
    override fun prime() {
        player.primeForPlayback()
    }

    override fun withdraw() {
        player.pause()
    }

    override fun release() {
        player.releasePlayer()
    }
}
