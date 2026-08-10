package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.dto.auth.DEVICE_FIELD_MAX
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.client.device.DeviceInfoProvider
import kotlinx.browser.window
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The browser app shell's contributions to the shared Koin graph — not purely about playback
 * despite the name, which stays as-is: [jsSharedModules] already appends it the same way
 * `iosPlaybackModule` is appended on iOS, because it is the app shell, not the platform, that
 * decides what these bindings are. Two unrelated concerns live here:
 *
 * - `playbackAvailable` — **false**: no web player exists yet, so `BookAvailability` gates the
 *   play and download paths off and the UI says so instead of offering a button that does
 *   nothing. This is a statement about today, not about browsers — unlike discovery or offline
 *   storage, audio playback on the web is entirely possible, and this module is where it grows.
 * - [DeviceInfoProvider] — every other platform binds this (see `iosPlaybackModule`, Android's
 *   `ListenUp.kt`, desktop's `PlatformModule.kt`); the browser had no binding at all, which is
 *   what actually broke sign-in, setup, and registration on the web — all three route through a
 *   use case that requires it. `platformVersion` is left `null` rather than guessed: a browser
 *   cannot reliably learn its host OS version without user-agent sniffing, and a wrong value is
 *   worse than an absent one. `deviceName` is the user agent, truncated to [DEVICE_FIELD_MAX] —
 *   [DeviceInfo]'s own length invariant throws otherwise, and `navigator.userAgent` routinely
 *   exceeds it. It is ugly in a device list, but it is the only string a browser has that tells
 *   "Chrome on my laptop" apart from "Safari on my iPad", which is exactly what that list is for.
 */
internal val browserPlaybackModule: Module =
    module {
        single(qualifier = named("playbackAvailable")) { false }

        // Structured device identity — shared source for auth login + listening history.
        single<DeviceInfoProvider> {
            val clientVersion = get<String>(named("clientVersion"))
            DeviceInfoProvider {
                DeviceInfo(
                    deviceType = "browser",
                    platform = "Web",
                    platformVersion = null,
                    clientName = "ListenUp Web",
                    clientVersion = clientVersion,
                    deviceName = window.navigator.userAgent.take(DEVICE_FIELD_MAX),
                )
            }
        }
    }
