package com.calypsan.listenup.client

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.work.Configuration
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.calypsan.listenup.client.core.ImageLoaderFactory
import com.calypsan.listenup.client.data.remote.PlaybackApi
import com.calypsan.listenup.client.data.remote.PlaybackApiContract
import com.calypsan.listenup.client.di.playbackPresentationModule
import com.calypsan.listenup.client.di.sharedModules
import com.calypsan.listenup.client.download.AndroidDownloadEnqueuer
import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.client.download.DownloadFileManager
import com.calypsan.listenup.client.download.DownloadManager
import com.calypsan.listenup.client.features.bookdetail.AndroidBookDetailPlatformActions
import com.calypsan.listenup.client.features.bookdetail.BookDetailPlatformActions
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.download.ListenUpWorkerFactory
import com.calypsan.listenup.client.automotive.BrowseTreeProvider
import com.calypsan.listenup.client.shortcuts.ListenUpShortcutManager
import com.calypsan.listenup.client.playback.AndroidAudioCapabilityDetector
import com.calypsan.listenup.client.playback.AndroidAudioTokenProvider
import com.calypsan.listenup.client.playback.CachedAudioTokenProvider
import com.calypsan.listenup.client.playback.AudioCapabilityDetector
import com.calypsan.listenup.client.playback.AudioTokenProvider
import com.calypsan.listenup.client.playback.AndroidPlaybackController
import com.calypsan.listenup.client.playback.MediaControllerHolder
import com.calypsan.listenup.client.playback.asControllerHolder
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackErrorHandler
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackManagerImpl
import com.calypsan.listenup.client.playback.ProgressTracker
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.sync.AndroidBackgroundSyncScheduler
import com.calypsan.listenup.client.sync.BackgroundSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.calypsan.listenup.client.domain.model.AuthState
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Android-specific dependencies module.
 * Contains ViewModels and other Android-specific components.
 */
val androidModule =
    module {
        viewModelOf(::InstanceViewModel)

        // Background sync scheduler
        single<BackgroundSyncScheduler> { AndroidBackgroundSyncScheduler(androidContext()) }

        // App shortcuts manager - handles dynamic shortcuts for recent books
        single {
            ListenUpShortcutManager(
                context = androidContext(),
                homeRepository = get(),
                scope = get(),
            )
        }
    }

/**
 * Playback module for audio streaming.
 * Contains Media3 integration and playback state management.
 */
val playbackModule =
    module {
        // Device ID for listening events (stable across app reinstalls on Android 8+)
        single(qualifier = named("deviceId")) {
            val context: Context = get()
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "unknown-device"
        }

        // Application-scoped coroutine for progress tracking
        single(createdAtStart = false) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        // Audio token provider — shared core wrapped by the Android-specific
        // OkHttp-interceptor adapter. Both must be singletons so the
        // PlaybackService and the AudioTokenProvider consumers share the same
        // cached token.
        single {
            CachedAudioTokenProvider(
                authSession = get(),
                authRepository = get(),
                scope = get(),
            )
        }
        single { AndroidAudioTokenProvider(core = get()) }
        single<AudioTokenProvider> { get<AndroidAudioTokenProvider>() }

        // Progress tracker for position persistence and event recording
        single {
            ProgressTracker(
                downloadRepository = get(),
                listeningEventRepository = get(),
                syncApi = get(),
                pushSyncOrchestrator = get(),
                positionRepository = get(),
                pendingOperationRepository = get(),
                endPlaybackSessionHandler = get(),
                scope = get(),
            )
        }

        // Playback error handler (needs concrete Android type for onUnauthorized())
        single {
            PlaybackErrorHandler(
                progressTracker = get(),
                tokenProvider = get<AndroidAudioTokenProvider>(),
            )
        }

        // Playback API - handles codec negotiation for transcoding
        single<PlaybackApiContract> { PlaybackApi(clientFactory = get()) }

        // Audio capability detector - detects device codec support
        single<AudioCapabilityDetector> { AndroidAudioCapabilityDetector() }

        // Playback manager - orchestrates playback startup
        single<PlaybackManager> {
            PlaybackManagerImpl(
                serverConfig = get(),
                playbackPreferences = get(),
                bookDao = get(),
                audioFileDao = get(),
                chapterDao = get(),
                imageStorage = get(),
                progressTracker = get(),
                tokenProvider = get(),
                deviceContext = get(),
                downloadService = get(),
                playbackApi = get(),
                capabilityDetector = get(),
                syncApi = get(),
                scope = get(),
                bookRepository = get(),
            )
        }

        // Sleep timer manager - handles sleep timer state and countdown
        single {
            SleepTimerManager(scope = get())
        }

        // Browse tree provider for Android Auto
        single {
            BrowseTreeProvider(
                homeRepository = get(),
                bookDao = get(),
                seriesDao = get(),
                contributorDao = get(),
                downloadDao = get(),
                imageStorage = get(),
            )
        }

        // Shared MediaController holder - single connection for all ViewModels
        // Eliminates duplicate controller connections and state drift
        single {
            MediaControllerHolder(
                context = get(),
                playbackManager = get(),
                scope = get(),
            )
        }

        // PlaybackController seam backed by the shared MediaControllerHolder
        single<PlaybackController> {
            AndroidPlaybackController(
                holder = get<MediaControllerHolder>().asControllerHolder(),
            )
        }
    }

/**
 * Download module for offline audiobook downloads.
 * Contains download management and file storage components.
 */
val downloadModule =
    module {
        // Download file manager - handles local file operations
        single { DownloadFileManager(androidContext()) }

        // Download manager - coordinates download queue and state
        // Bound to DownloadService interface for shared code (PlaybackManager)
        // Uses localPreferences for WiFi-only download constraint
        single<DownloadService> {
            DownloadManager(
                downloadDao = get(),
                bookDao = get(),
                audioFileDao = get(),
                workManager = WorkManager.getInstance(androidContext()),
                fileManager = get(),
                localPreferences = get<com.calypsan.listenup.client.domain.repository.LocalPreferences>(),
                downloadRepository = get(),
                transactionRunner = get(),
            )
        }

        // Also expose the concrete type for Android-specific features
        single { get<DownloadService>() as DownloadManager }

        // DownloadEnqueuer seam — Android backend for DownloadRepository.resumeForAudioFile
        single<DownloadEnqueuer> {
            AndroidDownloadEnqueuer(
                workManager = WorkManager.getInstance(androidContext()),
                localPreferences = get(),
            )
        }

        // Platform actions for BookDetailScreen (download + playback integration)
        single<BookDetailPlatformActions> {
            AndroidBookDetailPlatformActions(
                context = androidContext(),
                downloadManager = get(),
                nowPlayingViewModel = get(),
                localPreferences = get(),
                networkMonitor = get(),
                playbackManager = get(),
            )
        }
    }

/**
 * ListenUp Application class.
 *
 * Initializes dependency injection, Coil image loading, and other app-wide concerns.
 */
class ListenUp :
    Application(),
    SingletonImageLoader.Factory,
    KoinComponent {
    override fun onCreate() {
        super.onCreate()

        // Initialize Koin dependency injection
        startKoin {
            // Use Android logger for Koin diagnostic messages
            androidLogger(Level.DEBUG)

            // Provide Android Context to Koin
            androidContext(this@ListenUp)

            // Load all shared and Android-specific modules
            modules(sharedModules + androidModule + playbackModule + playbackPresentationModule + downloadModule)
        }

        // Configure WorkManager with custom factory for dependency injection.
        // Must be done before verifyCriticalKoinBindings() because DownloadManager needs WorkManager.
        val workerFactory =
            ListenUpWorkerFactory(
                downloadRepository = get(),
                fileManager = get(),
                apiClientFactory = get(),
                playbackPreferences = get(),
                playbackApi = get(),
                capabilityDetector = get(),
                backupApi = get(),
                absImportApi = get(),
            )

        val workManagerConfig =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

        WorkManager.initialize(this, workManagerConfig)

        // Verify critical Koin bindings at startup to fail fast.
        // This catches DI misconfigurations before UI loads, providing clear error messages.
        verifyCriticalKoinBindings()

        // Schedule periodic background sync only after user authenticates
        get<CoroutineScope>().launch {
            val authSession = get<com.calypsan.listenup.client.domain.repository.AuthSession>()
            authSession.authState.first { it is AuthState.Authenticated }
            get<BackgroundSyncScheduler>().schedule()
        }
    }

    /**
     * Create singleton ImageLoader for Coil.
     *
     * Called once by Coil to initialize the app-wide ImageLoader.
     * Configured to load book covers from local file storage.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoaderFactory.create(context = this)

    /**
     * Verify that all critical Koin singletons can be resolved.
     *
     * This catches DI misconfigurations at startup, before any UI or background workers
     * try to use them. Issues are logged with clear error messages.
     */
    private fun verifyCriticalKoinBindings() {
        val criticalTypes: List<Pair<String, () -> Unit>> =
            listOf(
                "ServerConfig" to {
                    get<com.calypsan.listenup.client.domain.repository.ServerConfig>()
                },
                "AuthSession" to {
                    get<com.calypsan.listenup.client.domain.repository.AuthSession>()
                },
                "SyncManager" to {
                    get<com.calypsan.listenup.client.data.sync.SyncManagerContract>()
                },
                "ProgressTracker" to {
                    get<ProgressTracker>()
                },
                "PlaybackManager" to {
                    get<PlaybackManager>()
                },
                "PushSyncOrchestrator" to
                    {
                        get<com.calypsan.listenup.client.data.sync.push.PushSyncOrchestratorContract>()
                    },
            )

        criticalTypes.forEach { (name, resolver) ->
            try {
                resolver()
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Koin verification failed for $name. Check your module configuration.\n" +
                        "Error: ${e.message}",
                    e,
                )
            }
        }
    }
}
