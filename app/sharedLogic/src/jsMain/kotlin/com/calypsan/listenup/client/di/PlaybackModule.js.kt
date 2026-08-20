package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.dto.auth.DEVICE_FIELD_MAX
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.core.appCoroutineExceptionHandler
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.download.BrowserDownloadService
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.playback.AudioTokenProvider
import com.calypsan.listenup.client.playback.CachedAudioTokenProvider
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackManagerImpl
import com.calypsan.listenup.client.playback.PlaybackProgressReporter
import com.calypsan.listenup.client.playback.ProgressTracker
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

private const val PLAYBACK_SCOPE = "playbackScope"

/**
 * The browser app shell's contributions to the shared Koin graph — not purely about playback
 * despite the name, which stays as-is: [jsSharedModules] already appends it the same way
 * `iosPlaybackModule` is appended on iOS, because it is the app shell, not the platform, that
 * decides what these bindings are. Concerns living here:
 *
 * - `playbackAvailable` — **false**: `:app:webApp`'s `webPlaybackModule` overrides this to `true`
 *   once it supplies a real `AudioPlayer` / `PlaybackController` — see that module's KDoc. Until a
 *   consuming app appends it, `BookAvailability` gates the play and download paths off.
 * - [PlaybackManager] and its collaborators ([AudioTokenProvider], [ProgressTracker],
 *   [PlaybackProgressReporter], [DownloadService]) — bound here rather than in `:app:webApp`
 *   because [PlaybackManagerImpl] is `internal` to this module, the same reason
 *   `desktopPlaybackModule` (`PlaybackModule.jvm.kt`) lives beside `PlaybackManagerImpl` instead
 *   of in `:app:sharedUI`. [CachedAudioTokenProvider] is the same commonMain class iOS binds —
 *   nothing about token caching is browser-specific. [BrowserDownloadService] is a stub: offline
 *   audio in a browser is undesigned (see `DownloadFileManager`), so [PlaybackManagerImpl] gets a
 *   `DownloadService` that always reports nothing downloaded, matching desktop's own stub.
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

        // Playback-scoped coroutine scope, same shape as iOS's: the appCoroutineExceptionHandler
        // keeps an uncaught failure in a fire-and-forget playback launch from taking the tab down.
        single(qualifier = named(PLAYBACK_SCOPE)) {
            CoroutineScope(SupervisorJob() + IODispatcher + appCoroutineExceptionHandler)
        }

        single<DownloadService> { BrowserDownloadService() }

        // Audio token provider — shared core; no browser-specific surface needed.
        single<AudioTokenProvider> {
            CachedAudioTokenProvider(
                authSession = get(),
                authRepository = get(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
            )
        }

        // Position reporter for the PlaybackManagerImpl seam — the browser has no Media3
        // PlaybackService, so this is the only driver of listening-event recording here,
        // exactly like desktop's desktopPlaybackModule.
        single {
            PlaybackProgressReporter(
                progressTracker = get(),
                recorder = get(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
                localPreferences = get(),
            )
        }

        single {
            ProgressTracker(
                downloadRepository = get(),
                positionRepository = get(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
                errorBus = get(),
            )
        }

        single<PlaybackManager> {
            PlaybackManagerImpl(
                serverConfig = get(),
                playbackPreferences = get(),
                bookDao = get(),
                audioFileDao = get(),
                chapterDao = get(),
                imageStorage = get(),
                progressTracker = get(),
                reporter = get(),
                tokenProvider = get(),
                deviceContext = get(),
                downloadService = get(),
                prepareRepository = get(),
                channel = rpcChannel<BookService>(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
                bookSyncDomainHandler = get<SyncDomainHandler<BookSyncPayload>>(named(SyncDomains.BOOKS.name)),
                playbackBandwidthCoordinator = get(),
                localPreferences = get(),
            )
        }

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
