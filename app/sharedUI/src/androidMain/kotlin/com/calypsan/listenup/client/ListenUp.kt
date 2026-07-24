package com.calypsan.listenup.client

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ProfilingManager
import android.os.ProfilingResult
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.work.Configuration
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.calypsan.listenup.core.ImageLoaderFactory
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.notifications.NotificationChannels
import com.calypsan.listenup.client.di.androidDownloadModule
import com.calypsan.listenup.client.di.androidPlaybackModule
import com.calypsan.listenup.client.di.androidPlaybackPresentationModule
import com.calypsan.listenup.client.di.androidSharedModules
import com.calypsan.listenup.client.features.bookdetail.AndroidBookDetailPlatformActions
import com.calypsan.listenup.client.features.bookdetail.BookDetailPlatformActions
import com.calypsan.listenup.client.download.ListenUpWorkerFactory
import com.calypsan.listenup.client.automotive.BrowseTreeProvider
import com.calypsan.listenup.client.shortcuts.ListenUpShortcutManager
import com.calypsan.listenup.client.playback.AndroidAudioTokenProvider
import com.calypsan.listenup.client.playback.CachedAudioTokenProvider
import com.calypsan.listenup.client.playback.AudioTokenProvider
import com.calypsan.listenup.client.playback.AndroidPlaybackController
import com.calypsan.listenup.client.playback.MediaControllerHolder
import com.calypsan.listenup.client.playback.asControllerHolder
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackErrorHandler
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackStateWriter
import com.calypsan.listenup.client.playback.ProgressTracker
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.playback.cast.CastPreparer
import com.calypsan.listenup.client.sync.AndroidBackgroundSyncScheduler
import com.calypsan.listenup.client.sync.BackgroundSyncScheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
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
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val logger = KotlinLogging.logger {}

/** Maximum number of historical exit records to inspect for memory-related causes. */
private const val HISTORICAL_EXIT_LIMIT = 5

/** Maps [ApplicationExitInfo.REASON_*] codes to human-readable names for log output. */
@RequiresApi(Build.VERSION_CODES.R)
private val exitReasonNames: Map<Int, String> =
    mapOf(
        ApplicationExitInfo.REASON_UNKNOWN to "UNKNOWN",
        ApplicationExitInfo.REASON_EXIT_SELF to "EXIT_SELF",
        ApplicationExitInfo.REASON_SIGNALED to "SIGNALED",
        ApplicationExitInfo.REASON_LOW_MEMORY to "LOW_MEMORY",
        ApplicationExitInfo.REASON_CRASH to "CRASH",
        ApplicationExitInfo.REASON_CRASH_NATIVE to "CRASH_NATIVE",
        ApplicationExitInfo.REASON_ANR to "ANR",
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "INITIALIZATION_FAILURE",
        ApplicationExitInfo.REASON_PERMISSION_CHANGE to "PERMISSION_CHANGE",
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "EXCESSIVE_RESOURCE_USAGE",
        ApplicationExitInfo.REASON_USER_REQUESTED to "USER_REQUESTED",
        ApplicationExitInfo.REASON_USER_STOPPED to "USER_STOPPED",
        ApplicationExitInfo.REASON_DEPENDENCY_DIED to "DEPENDENCY_DIED",
        ApplicationExitInfo.REASON_OTHER to "OTHER",
        ApplicationExitInfo.REASON_FREEZER to "FREEZER",
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE to "PACKAGE_STATE_CHANGE",
        ApplicationExitInfo.REASON_PACKAGE_UPDATED to "PACKAGE_UPDATED",
    )

/** Returns a readable name for [code] in the form `NAME(int)`, e.g. `LOW_MEMORY(3)`. */
@RequiresApi(Build.VERSION_CODES.R)
private fun exitReasonName(code: Int): String = "${exitReasonNames[code] ?: "UNKNOWN"}($code)"

/**
 * Runs each named resolver and throws [IllegalStateException] on the first failure.
 *
 * Extracted as an internal top-level function so it can be called from tests
 * without a real Koin graph.  The [ListenUp] class calls this off the main thread
 * (see [ListenUp.verifyCriticalKoinBindings]) to avoid blocking the first frame.
 */
internal fun checkCriticalKoinBindings(resolvers: List<Pair<String, () -> Unit>>) {
    resolvers.forEach { (name, resolver) ->
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

/**
 * Android-specific dependencies module.
 * Contains ViewModels and other Android-specific components.
 */
val androidModule =
    module {
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

        // Platform capability flag: Android supports download and playback.
        single(qualifier = named("playbackAvailable")) { true }

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

        // Progress tracker for position persistence and playback-session tracking
        single {
            ProgressTracker(
                downloadRepository = get(),
                positionRepository = get(),
                scope = get(),
            )
        }

        // Structured device identity — shared source for auth login + listening history.
        single<DeviceInfoProvider> {
            val clientVersion = get<String>(named("clientVersion"))
            DeviceInfoProvider {
                DeviceInfo(
                    deviceType = "phone",
                    platform = "Android",
                    platformVersion = android.os.Build.VERSION.RELEASE,
                    clientName = "ListenUp Android",
                    clientVersion = clientVersion,
                    deviceName = null,
                    deviceModel = android.os.Build.MODEL,
                )
            }
        }

        // Playback error handler (needs concrete Android type for onUnauthorized())
        single {
            PlaybackErrorHandler(
                progressTracker = get(),
                tokenProvider = get<AndroidAudioTokenProvider>(),
            )
        }

        // Bind the PlaybackStateWriter write-seam to the same PlaybackManager
        // singleton. MediaControllerHolder depends on PlaybackStateWriter (not the
        // full PlaybackManager), and Koin resolves by the requested type — without
        // this delegate the write-seam is unbound and MediaControllerHolder fails.
        single<PlaybackStateWriter> { get<PlaybackManager>() }

        // Sleep timer manager - handles sleep timer state and countdown
        single {
            SleepTimerManager(scope = get())
        }

        // Browse tree provider for Android Auto
        single {
            BrowseTreeProvider(
                homeRepository = get(),
                bookRepository = get(),
                seriesRepository = get(),
                contributorRepository = get(),
                downloadRepository = get(),
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

        // Cast preparer — re-fetches signed network URLs when handing off to a Chromecast.
        single { CastPreparer(prepareRepository = get(), serverConfig = get()) }
    }

/**
 * UI-facing download wiring. The DB-touching bindings (`DownloadFileManager`,
 * `DownloadManager`, `AndroidDownloadEnqueuer`) live in `:app:sharedLogic`'s
 * [androidDownloadModule] so they can construct against the `internal` Room DAOs;
 * this module keeps only the `:app:sharedUI`-coupled platform-actions binding.
 */
val downloadModule =
    module {
        // Platform actions for BookDetailScreen (download + playback integration)
        single<BookDetailPlatformActions> {
            AndroidBookDetailPlatformActions(
                context = androidContext(),
                downloadManager = get(),
                nowPlayingViewModel = get(),
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

        // Register all notification channels before any service or worker can post a notification.
        // Idempotent — safe to call on every startup.
        NotificationChannels.registerAll(this)

        // Initialize Koin dependency injection
        startKoin {
            // INFO, not DEBUG: Koin's DEBUG level logs every dependency resolution, which floods
            // logcat during sync (e.g. per-event SyncCursorStore lookups across a 1000-book scan).
            androidLogger(Level.INFO)

            // Provide Android Context to Koin
            androidContext(this@ListenUp)

            // Load all shared and Android-specific modules
            modules(
                androidSharedModules() + androidModule + playbackModule + androidPlaybackModule +
                    androidPlaybackPresentationModule() + androidDownloadModule + downloadModule,
            )
        }

        // Configure WorkManager with custom factory for dependency injection.
        // Dependencies are wrapped in lazy { get() } so constructing the factory does not
        // resolve DownloadRepository → DownloadEnqueuer → WorkManager.getInstance() before
        // WorkManager.initialize() is called below.
        val workerFactory =
            ListenUpWorkerFactory(
                downloadRepository = lazy { get() },
                fileManager = lazy { get() },
                audioFileDownloader = lazy { get() },
                errorBus = lazy { get() },
            )

        val workManagerConfig =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

        WorkManager.initialize(this, workManagerConfig)

        // Verify critical Koin bindings off the first-frame path.
        // Launching on Default keeps DI resolution (including Media3 session init and
        // ProgressTracker construction) off the main thread, saving ~30–80 ms of
        // cold-start latency.  Fail-fast is intentional and preserved: any exception
        // is re-thrown on the main thread so the process terminates immediately and
        // visibly — a misconfigured build must never silently continue.
        get<CoroutineScope>().launch(Dispatchers.Default) {
            runCatching { verifyCriticalKoinBindings() }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                Handler(Looper.getMainLooper()).post { throw failure }
            }
        }

        // Register ProfilingManager callback to surface system-driven OOM/anomaly results.
        // Android 17+ (API 37) only — no-op on earlier releases.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            registerProfilingTriggers()
        }

        // Log memory-related historical exit reasons so operators can diagnose OOM crashes
        // from logs alone, without requiring ADB or on-device tooling.
        logHistoricalMemoryExits()

        // Schedule periodic background sync only after user authenticates
        get<CoroutineScope>().launch {
            val authSession = get<com.calypsan.listenup.client.domain.repository.AuthSession>()
            authSession.authState.first { it is AuthState.Authenticated }
            com.calypsan.listenup.client.data.remote
                .warmUpApiClient()
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
     * Registers a [ProfilingManager] callback that logs the outcome of every system-driven
     * profiling request (heap dumps, stack samples, system traces) at INFO level.
     *
     * This gives self-hosting operators a passive view into OOM and anomaly events without
     * requiring ADB or on-device tooling. Any registration failure is caught and logged at
     * WARN so that a diagnostics setup problem can never crash app startup.
     *
     * API gate: Android 17+ ([Build.VERSION_CODES.CINNAMON_BUN], API 37).
     */
    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    private fun registerProfilingTriggers() {
        runCatching {
            val profilingManager =
                getSystemService(ProfilingManager::class.java) ?: return@runCatching
            profilingManager.registerForAllProfilingResults(mainExecutor) { result: ProfilingResult ->
                logger.info {
                    "ProfilingManager result: errorCode=${result.errorCode} " +
                        "triggerType=${result.triggerType} " +
                        "tag=${result.tag} " +
                        "resultFilePath=${result.resultFilePath}"
                }
            }
        }.onFailure { t ->
            logger.warn(t) { "ProfilingManager registration failed" }
        }
    }

    /**
     * Reads up to [HISTORICAL_EXIT_LIMIT] historical process exit records and logs any that
     * are memory-related (description contains "memory" or reason is [REASON_LOW_MEMORY]).
     *
     * Operators can correlate these log lines with OOM reports or crash analytics without
     * needing ADB access. Any failure is caught and logged at WARN.
     *
     * API gate: Android 11+ ([Build.VERSION_CODES.R], API 30).
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun logHistoricalMemoryExits() {
        runCatching {
            val activityManager =
                getSystemService(ActivityManager::class.java) ?: return@runCatching
            val records = activityManager.getHistoricalProcessExitReasons(packageName, 0, HISTORICAL_EXIT_LIMIT)
            records.forEach { record ->
                val description = record.description
                val isMemoryRelated =
                    record.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                        description?.contains("memory", ignoreCase = true) == true
                if (isMemoryRelated) {
                    logger.info {
                        "Historical exit: reason=${exitReasonName(record.reason)} description=$description " +
                            "pss=${record.pss}kB rss=${record.rss}kB"
                    }
                }
            }
        }.onFailure { t ->
            logger.warn(t) { "Failed to read historical exit reasons" }
        }
    }

    /**
     * Verify that all critical Koin singletons can be resolved.
     *
     * This catches DI misconfigurations at startup, before any UI or background workers
     * try to use them.  Delegates to [checkCriticalKoinBindings] which holds the
     * throw logic and is independently testable.
     */
    private fun verifyCriticalKoinBindings() {
        checkCriticalKoinBindings(
            listOf(
                "ServerConfig" to {
                    get<com.calypsan.listenup.client.domain.repository.ServerConfig>()
                },
                "AuthSession" to {
                    get<com.calypsan.listenup.client.domain.repository.AuthSession>()
                },
                "SyncRepository" to {
                    get<com.calypsan.listenup.client.domain.repository.SyncRepository>()
                },
                "ProgressTracker" to {
                    get<ProgressTracker>()
                },
                "PlaybackManager" to {
                    get<PlaybackManager>()
                },
                // Resolves MediaControllerHolder → PlaybackStateWriter transitively,
                // so a missing playback write-seam binding fails fast at startup
                // rather than crashing deep in NowPlaying recomposition.
                "PlaybackController" to {
                    get<PlaybackController>()
                },
            ),
        )
    }
}
