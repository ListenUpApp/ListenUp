package com.calypsan.listenup.web.di

import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.web.playback.HtmlAudioPlayer
import com.calypsan.listenup.web.playback.WebPlaybackController
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

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
 * interface); anything reading [AudioPlayer] gets the identical instance — pinned by
 * `WebPlaybackModuleTest`'s same-instance assertion, since a future edit that "simplifies" this to
 * a single `single<AudioPlayer> { HtmlAudioPlayer() }` would compile clean and pass every OTHER
 * test while quietly splitting the transport bar and [WebPlaybackController] onto two different,
 * one-of-them-silent `<audio>` elements.
 *
 * `onClose { it?.releasePlayer() }` matters for the same reason `browserPlaybackModule`'s
 * playback-scoped `CoroutineScope` cancels on close: a browser tab's Koin graph starts and stops
 * far more often than a native process exits (once per spec in this test suite), and without it
 * every `stopKoin()` would leave a detached `<audio>` element with live DOM listeners on the
 * shared test page.
 */
internal val webPlaybackModule: Module =
    module {
        single { HtmlAudioPlayer() } onClose { it?.releasePlayer() }
        single<AudioPlayer> { get<HtmlAudioPlayer>() }

        single<PlaybackController> {
            WebPlaybackController(audioPlayer = get(), playbackManager = get())
        }

        // Overrides browserPlaybackModule's `false`. Loading last is what makes this win, the
        // same way platformStorageModule overrides mediaModule's DocumentStorage.
        single(qualifier = named("playbackAvailable")) { true }
    }
