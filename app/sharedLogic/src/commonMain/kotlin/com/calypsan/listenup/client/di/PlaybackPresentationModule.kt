package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.playback.PlaybackControllerActivator
import org.koin.dsl.module

/**
 * Koin module providing [PlaybackControllerActivator], which acquires the `PlaybackController`
 * connection exactly once, the first time something resolves it.
 *
 * It is deliberately NOT `createdAtStart`: on Android the first acquire binds `PlaybackService`,
 * which builds ExoPlayer and the MediaSession, and doing that at Koin startup put it ahead of the
 * first frame and on every FCM or WorkManager wake. Android resolves it from the two places a
 * controller is actually needed — `MainActivity` (after its first frame) and
 * `PlaybackService.onCreate` (a media button, Android Auto or a resumption request starting the
 * process with no activity). Desktop's `acquire()` is a no-op, so nothing there needs to resolve it.
 *
 * Keeping acquisition out of any ViewModel is what lets `PlayerViewModel` (bound in `playerModule`)
 * be a plain `factory` like every other ViewModel: a store clearing kills only that store's VM
 * instance, never the controller connection.
 */
internal val playbackPresentationModule =
    module {
        single { PlaybackControllerActivator(playbackController = get()) }
    }
