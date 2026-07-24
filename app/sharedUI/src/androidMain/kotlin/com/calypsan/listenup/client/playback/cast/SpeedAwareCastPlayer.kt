package com.calypsan.listenup.client.playback.cast

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Pushes a playback rate to the Cast receiver. Implemented over `RemoteMediaClient.setPlaybackRate()`. */
fun interface CastRateSetter {
    fun setRate(rate: Double)
}

/**
 * Wraps Media3's [androidx.media3.cast.CastPlayer] so playback-speed changes work.
 *
 * `CastPlayer` declares `setPlaybackParameters` unsupported (ExoPlayer issue
 * #6784), but the Cast Default Media Receiver honors `setPlaybackRate`. We
 * intercept the speed command, forward it to the receiver via [rateSetter], and
 * remember it so [getPlaybackParameters] reports the live rate — keeping the
 * UI speed control truthful while casting.
 */
@OptIn(UnstableApi::class)
class SpeedAwareCastPlayer(
    castPlayer: Player,
    private val rateSetter: CastRateSetter,
) : ForwardingPlayer(castPlayer) {
    /**
     * Local mirror of the last rate we pushed — authoritative only because this app is the
     * sole rate-writer. It is NOT synced receiver state: it starts at [PlaybackParameters.DEFAULT]
     * and would lag a pre-existing remote rate when resuming an already-playing Cast session.
     */
    private var currentParameters: PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        currentParameters = playbackParameters
        rateSetter.setRate(playbackParameters.speed.toDouble())
    }

    override fun setPlaybackSpeed(speed: Float) {
        setPlaybackParameters(PlaybackParameters(speed))
    }

    override fun getPlaybackParameters(): PlaybackParameters = currentParameters

    override fun isCommandAvailable(command: Int): Boolean =
        command == Player.COMMAND_SET_SPEED_AND_PITCH || super.isCommandAvailable(command)

    override fun getAvailableCommands(): Player.Commands =
        super
            .getAvailableCommands()
            .buildUpon()
            .add(Player.COMMAND_SET_SPEED_AND_PITCH)
            .build()
}
