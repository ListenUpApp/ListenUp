package com.calypsan.listenup.web.di

import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.web.playback.HtmlAudioPlayer
import com.calypsan.listenup.web.playback.WebPlaybackController
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The browser app shell's playback bindings — the seam `browserPlaybackModule`
 * (`:app:sharedLogic`'s `PlaybackModule.js.kt`) left open on purpose, per its own KDoc: "no web
 * player exists yet ... this module is where it grows." It has grown.
 *
 * Loaded LAST in `Main.kt`'s `modules(jsSharedModules() + webPlaybackModule + …)`, the same
 * later-definition-wins pattern `platformStorageModule` already uses to override `mediaModule`'s
 * filesystem `DocumentStorage` — here it overrides `browserPlaybackModule`'s
 * `playbackAvailable = false` with `true`, which is what lets `BookAvailability` (and therefore
 * the UI) offer a play button at all.
 *
 * [HtmlAudioPlayer] is bound once, under its own type, and re-exposed under [AudioPlayer] by
 * fetching that same singleton — not a second `single<AudioPlayer> { HtmlAudioPlayer() }`, which
 * would construct a second `<audio>` element nothing plays through. [WebPlaybackController] needs
 * the concrete type (for [HtmlAudioPlayer.setVolume], which is not part of the shared [AudioPlayer]
 * interface); anything reading [AudioPlayer] gets the identical instance.
 */
internal val webPlaybackModule: Module =
    module {
        single { HtmlAudioPlayer() }
        single<AudioPlayer> { get<HtmlAudioPlayer>() }

        single<PlaybackController> {
            WebPlaybackController(audioPlayer = get(), playbackManager = get())
        }

        // Overrides browserPlaybackModule's `false`. Loading last is what makes this win, the
        // same way platformStorageModule overrides mediaModule's DocumentStorage.
        single(qualifier = named("playbackAvailable")) { true }
    }
