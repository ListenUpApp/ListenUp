package com.calypsan.listenup.client.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Builds the audiobook-configured [ExoPlayer] used by [PlaybackService]:
 * streaming OkHttp client with finite stall timeouts, artwork-free MP4 extraction,
 * the volume-boost [GainAudioProcessor] stage, speech audio attributes, and noisy/wake handling.
 */
@OptIn(UnstableApi::class)
internal fun buildAudiobookExoPlayer(
    context: Context,
    tokenProvider: AndroidAudioTokenProvider,
    gainProcessor: GainAudioProcessor,
    listener: Player.Listener,
): ExoPlayer {
    // Create OkHttp client with auth interceptor. Timeouts — and why they are all
    // finite — live in StallRecovery.kt.
    val okHttpClient =
        buildStreamingHttpClient(
            authInterceptor = tokenProvider.createInterceptor(),
            tokenAuthenticator = tokenProvider.createAuthenticator(),
        )

    // Create DataSource factory: OkHttp for HTTP(S) streaming, DefaultDataSource
    // wrapper to also handle file:// URIs for downloaded audiobooks
    val httpDataSourceFactory: DataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
    val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context, httpDataSourceFactory)

    // Every cover we display comes from our own library (MediaItem.artworkUri, set in
    // AndroidPlaybackController and BrowseTreeProvider) — nothing reads MediaMetadata.artworkData.
    // Media3 1.11's FLAG_DISABLE_ARTWORK_METADATA stops the MP4 extractor decoding the embedded
    // cover art it would otherwise hold for the whole session. Audiobook m4b files routinely
    // carry megabyte-scale artwork, and a multi-file book pays that per file.
    val extractorsFactory =
        DefaultExtractorsFactory()
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_DISABLE_ARTWORK_METADATA)

    // Create media source factory
    val mediaSourceFactory =
        DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(dataSourceFactory)

    // Platform-default AAC decoding. We deliberately do NOT set the DRC target reference
    // level: Android's default is -16 LKFS, matching every other audio app on the device.
    // A previous custom RenderersFactory set -24 LKFS, which attenuated all AAC playback
    // by 8 dB. Dynamic-range control on AAC-LC comes from KEY_AAC_DRC_HEAVY_COMPRESSION,
    // which already defaults to heavy — we get it without configuring anything.
    //
    // GainRenderersFactory is the stock factory with one addition: the volume-boost gain
    // stage in the audio sink's processor chain. Everything else about the sink, including
    // the decode path above, is unchanged.
    val renderersFactory =
        GainRenderersFactory(context, gainProcessor)
            .setEnableDecoderFallback(true)

    // Build ExoPlayer with audiobook-optimized settings
    val player =
        ExoPlayer
            .Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH) // Audiobooks!
                    .build(),
                // handleAudioFocus =
                true,
            ).setHandleAudioBecomingNoisy(true) // Pause when headphones unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL) // Keep CPU awake during playback
            // No setSeek{Back,Forward}IncrementMs here on purpose: those are builder-only,
            // so honouring a Settings change through them would mean rebuilding the player
            // mid-sentence. SkipIntervalPlayer (installed in initializeMediaSession) reads
            // the live value per press instead — see its KDoc.
            // Backstop for a stall the socket-read timeout can't see — see StallRecovery.kt.
            // Media3's own default is ten minutes, which strands the listener.
            .setStuckBufferingDetectionTimeoutMs(STUCK_BUFFERING_TIMEOUT_MS)
            .build()
            .apply {
                addListener(listener)

                // No audio offload. Offload hands compressed frames straight to the DSP, which
                // saves battery but bypasses the AudioProcessor chain entirely — so both the
                // volume-boost gain stage and the R128 loudness measurement need the standard
                // decode path to exist at all. Re-enabling offload must be gated on the
                // effective gain being exactly 0 dB, and even then costs the measurement.
            }

    logger.info { "ExoPlayer initialized with the volume-boost gain stage" }
    return player
}
