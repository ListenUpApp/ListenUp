package com.calypsan.listenup.client.di

import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Browser playback module — the capability flag and, in time, the web player behind it.
 *
 * `playbackAvailable` is **false**: no web player exists yet, so `BookAvailability` gates the
 * play and download paths off and the UI says so instead of offering a button that does nothing.
 * This is a statement about today, not about browsers — unlike discovery or offline storage,
 * audio playback on the web is entirely possible, and this module is where it grows.
 *
 * Registered the same way iOS registers `iosPlaybackModule`: appended to the shared list at
 * Koin start rather than hidden inside a platform module, because it is the app shell — not the
 * platform — that decides whether playback exists.
 */
internal val browserPlaybackModule: Module =
    module {
        single(qualifier = named("playbackAvailable")) { false }
    }
