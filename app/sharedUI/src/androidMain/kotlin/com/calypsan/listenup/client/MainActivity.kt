package com.calypsan.listenup.client

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.playback.PlaybackStateProvider
import com.calypsan.listenup.client.handoff.resolveHandoffTarget
import com.calypsan.listenup.client.handoff.ViewedBookTracker
import com.calypsan.listenup.client.handoff.HandoffTarget
import androidx.core.net.toUri
import android.os.PersistableBundle
import android.os.Build
import android.content.ComponentName
import android.app.HandoffActivityDataRequestInfo
import android.app.HandoffActivityData
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.notifications.toNotificationEvent
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.data.connection.ConnectionCoordinator
import com.calypsan.listenup.client.data.repository.DeepLinkManager
import com.calypsan.listenup.client.data.repository.ShortcutAction
import com.calypsan.listenup.client.data.repository.ShortcutActionManager
import com.calypsan.listenup.client.share.ShareLinkCodec
import com.calypsan.listenup.client.share.ShareTarget
import com.calypsan.listenup.client.design.haptics.ProvideHaptics
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.navigation.ListenUpNavigation
import com.calypsan.listenup.client.shortcuts.ShortcutActions
import com.calypsan.listenup.client.foldable.PostureProvider
import com.calypsan.listenup.client.presentation.notifications.toShortcutAction
import com.calypsan.listenup.client.presentation.startup.AppStartupViewModel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import org.koin.androidx.viewmodel.ext.android.viewModel

private val logger = KotlinLogging.logger {}

/**
 * Main activity for the ListenUp app.
 *
 * Manages realtime sync lifecycle:
 * - Connects realtime sync when app comes to foreground (if authenticated)
 * - Disconnects realtime sync when app goes to background (saves battery)
 * - Auto-reconnects on app resume
 *
 * Handles share / deep links via the https App Link (https://link.listenup.audio/o?...).
 *
 * This ensures real-time updates when actively using the app
 * while preserving battery life in the background.
 */
private const val HANDOFF_MIN_SDK = 37

/** The handoff extra: a share link, so the receiver reuses the App-Link path. */
private const val EXTRA_HANDOFF_LINK = "com.calypsan.listenup.HANDOFF_LINK"

class MainActivity : ComponentActivity() {
    private val authSession: AuthSession by inject()
    private val syncRepository: SyncRepository by inject()
    private val localPreferences: LocalPreferences by inject()
    private val connectionCoordinator: ConnectionCoordinator by inject()
    private val deepLinkManager: DeepLinkManager by inject()
    private val shortcutActionManager: ShortcutActionManager by inject()
    private val viewedBookTracker: ViewedBookTracker by inject()
    private val playbackStateProvider: PlaybackStateProvider by inject()
    private val instanceRepository: InstanceRepository by inject()

    /**
     * The server's identity, resolved once at startup and read synchronously during a handoff.
     *
     * ⛔ Not laziness — a necessity. `onHandoffActivityDataRequested` is a synchronous platform
     * callback and every `InstanceRepository` accessor suspends, so the value has to already be
     * here when the system asks. Staleness is a non-issue: a server's instance id and remote URL
     * are the two things about it that essentially never change, and a null merely produces a
     * link without them, which the codec already treats as optional.
     */
    @Volatile
    private var serverIdentity: ServerInfo? = null
    private val appStartupViewModel: AppStartupViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Keep the splash on screen while AppStartupViewModel is still performing its
        // cold-start library-setup check. The lambda is polled each frame.
        splash.setKeepOnScreenCondition { appStartupViewModel.state.value.isChecking }

        // Handle deep link from initial launch
        handleIntent(intent)

        // Initialize local preferences (theme, dynamic colors, etc.) from storage
        lifecycleScope.launch {
            localPreferences.initializeLocalPreferences()
        }

        // Connect realtime sync as soon as auth becomes Authenticated — including
        // mid-session (a fresh onboarding), not only on the next onResume/restart.
        // Without this, a just-registered user has no live sync stream, so books the
        // server scans right after library creation don't arrive until the app is
        // relaunched. Re-collected per STARTED so foreground resumes reconnect too;
        // engine.start() is single-flight, so overlapping resumes are safe. The
        // teardown half lives in the same window — see SyncLifecyclePolicy for why
        // onPause was the wrong signal for it.
        val syncLifecycle =
            SyncLifecyclePolicy(syncRepository) {
                authSession.authState.map { it is AuthState.Authenticated }
            }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { syncLifecycle.runWhileStarted() }
        }

        setContent {
            PostureProvider {
                ListenUpApp()
            }
        }

        // Continue On (API 37). Guarded because minSdk is 33: the methods are final on Activity and
        // simply do not exist on older platforms, so this is a version check rather than a
        // capability one. Enabling is all the setup there is — no manifest entry, no permission.
        if (Build.VERSION.SDK_INT >= HANDOFF_MIN_SDK) {
            setHandoffEnabled(true, null)
            lifecycleScope.launch { serverIdentity = instanceRepository.getServerInfoOrNull() }
        }
    }

    /**
     * What this phone offers another device, asked live by the system.
     *
     * ⛔ The extras carry a LINK, not a book id, and that is the design. `ShareLinkCodec` already
     * produces `https://link.listenup.audio/o?t=book&b=…&i=…&u=…` — a verified App Link this very
     * activity already decodes for sharing — so the receiver reuses [handleIntent]'s existing path
     * instead of growing a second one. The same string is the fallback URI, which means a receiver
     * WITHOUT the app opens the web client on the same book. One URL, three consumers.
     *
     * ⛔ Position is deliberately absent. The server is the source of truth for where the reader
     * is in a book and already syncs it across devices, so shipping a timestamp here would create a
     * second, staler answer to a question already answered. The 50 KB extras budget is never
     * approached; this carries one URL.
     */
    override fun onHandoffActivityDataRequested(
        handoffRequestInfo: HandoffActivityDataRequestInfo,
    ): HandoffActivityData? {
        val target =
            resolveHandoffTarget(
                playingBookId = playbackStateProvider.currentBookId.value,
                viewedBookId = viewedBookTracker.viewedBookId.value,
            )
        if (target !is HandoffTarget.Book) return null

        val server = serverIdentity
        val link =
            ShareLinkCodec.encode(
                ShareTarget.Book(
                    bookId = target.bookId,
                    serverInstanceId = server?.instanceId,
                    serverUrl = server?.remoteUrl?.trimEnd('/'),
                ),
            )

        return HandoffActivityData
            .Builder(ComponentName(this, MainActivity::class.java))
            .setExtras(PersistableBundle().apply { putString(EXTRA_HANDOFF_LINK, link) })
            .setFallbackUri(link.toUri())
            .build()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle deep link when app is already running (singleTask)
        handleIntent(intent)
    }

    /**
     * Parses and stores deep link or shortcut action for navigation layer to consume.
     *
     * Handles:
     * - Share / deep links (https App Links: book + invite)
     * - App shortcut actions (RESUME, PLAY_BOOK, SEARCH, SLEEP_TIMER)
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // A Continue On arrival. Read FIRST: a handoff launch carries our extra and may also carry
        // an action of its own, and the link is the more specific instruction either way. Decoding
        // it through the same codec as an App Link means the receiving half of this feature is the
        // path that was already built and already tested — a handoff and a shared link land the
        // reader in exactly the same place, because they are the same string.
        intent.getStringExtra(EXTRA_HANDOFF_LINK)?.let { raw ->
            val target = ShareLinkCodec.decode(raw)
            if (target != null) {
                logger.debug { "Continue On handoff received" }
                deepLinkManager.setPendingTarget(target)
                return
            }
            logger.warn { "Continue On handoff did not decode: ${redactLinkForLog(raw)}" }
        }

        // Share / deep links (https App Links) — parsed once, in commonMain.
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.toString()?.let { raw ->
                val target = ShareLinkCodec.decode(raw)
                if (target != null) {
                    logger.debug {
                        when (target) {
                            is ShareTarget.Book -> "Received book share link: bookId=${target.bookId.value}"
                            is ShareTarget.Invite -> "Received invite share link"
                        }
                    }
                    deepLinkManager.setPendingTarget(target)
                    return
                }
                // Diagnosability: an App-Link VIEW reached our host but did not decode to a share
                // target (e.g. a stripped/malformed link). Log it so one logcat pinpoints the failure
                // instead of the previous silent fall-through to auth routing. Values are redacted
                // because an invite `code` is a bearer secret and this log file is user-exportable.
                logger.warn {
                    "ACTION_VIEW did not decode to a share target: ${redactLinkForLog(raw)}"
                }
            }
        }

        // Check for shortcut actions
        when (intent.action) {
            ShortcutActions.PUSH_TAP -> {
                // Decode the payload the renderer wrote and route through the SAME target mapping
                // as the in-app notification list — one mapping, so the two surfaces cannot
                // disagree. Anything that doesn't decode or doesn't map — including a payload from
                // a server newer than this client — yields null and simply opens the app: a tap
                // must never be worse than a no-op.
                val action =
                    intent
                        .getStringExtra(ShortcutActions.EXTRA_PUSH_PAYLOAD)
                        ?.let { raw ->
                            runCatching { contractJson.decodeFromString(PushPayload.serializer(), raw) }.getOrNull()
                        }?.toNotificationEvent()
                        ?.toShortcutAction()
                logger.debug { "Push tapped: action=${action?.let { it::class.simpleName }}" }
                action?.let(shortcutActionManager::setPendingAction)
            }

            ShortcutActions.RESUME -> {
                logger.debug { "Received RESUME shortcut action" }
                shortcutActionManager.setPendingAction(ShortcutAction.Resume)
            }

            ShortcutActions.PLAY_BOOK -> {
                val bookId = intent.getStringExtra(ShortcutActions.EXTRA_BOOK_ID)
                if (bookId != null) {
                    logger.debug { "Received PLAY_BOOK shortcut action - bookId=$bookId" }
                    shortcutActionManager.setPendingAction(ShortcutAction.PlayBook(bookId))
                } else {
                    logger.warn { "PLAY_BOOK action missing book_id extra" }
                }
            }

            ShortcutActions.SEARCH -> {
                logger.debug { "Received SEARCH shortcut action" }
                shortcutActionManager.setPendingAction(ShortcutAction.Search)
            }

            ShortcutActions.SLEEP_TIMER -> {
                val timerMinutes =
                    intent
                        .getIntExtra(ShortcutActions.EXTRA_TIMER_MINUTES, -1)
                        .takeIf { it > 0 }
                logger.debug { "Received SLEEP_TIMER shortcut action - minutes=$timerMinutes" }
                shortcutActionManager.setPendingAction(ShortcutAction.SleepTimer(timerMinutes))
            }

            ShortcutActions.NAVIGATE_TO_ABS_IMPORT -> {
                val importId = intent.getStringExtra(ShortcutActions.EXTRA_IMPORT_ID)
                if (importId != null) {
                    logger.debug { "Received NAVIGATE_TO_ABS_IMPORT action - importId=$importId" }
                    shortcutActionManager.setPendingAction(ShortcutAction.NavigateToAbsImport(importId))
                } else {
                    logger.warn { "NAVIGATE_TO_ABS_IMPORT action missing import_id extra" }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Notify startup ViewModel that the app is foregrounding.
        // Short resumes (< 5 min) skip the library-setup re-check;
        // long background periods trigger a fresh check.
        appStartupViewModel.onAppForegrounded()

        // Re-evaluate the reachable server URL (prefer LAN) when foregrounding.
        lifecycleScope.launch {
            connectionCoordinator.reevaluate()
        }
    }

    override fun onPause() {
        super.onPause()

        // Record background timestamp so onAppForegrounded can decide
        // whether a library-setup re-check is needed on the next resume.
        appStartupViewModel.onAppBackgrounded()
    }
}

/**
 * Root composable for the ListenUp app.
 *
 * Wraps the entire app in Material 3 Expressive theme with:
 * - Dynamic color support (Android 12+)
 * - Display P3 HDR color space
 * - Google Sans Flex typography
 * - Expressive shapes (20-28dp corners)
 *
 * Theme respects user preferences:
 * - ThemeMode: System (default), Light, or Dark
 * - Dynamic colors: On/Off (Android 12+ only)
 *
 * Navigation is auth-driven and automatically adjusts based on
 * authentication state from SettingsRepository.
 */
@Composable
fun ListenUpApp(localPreferences: LocalPreferences = koinInject()) {
    // Observe theme preferences
    val themeMode by localPreferences.themeMode.collectAsStateWithLifecycle()
    val dynamicColorsEnabled by localPreferences.dynamicColorsEnabled.collectAsStateWithLifecycle()
    val hapticFeedbackEnabled by localPreferences.hapticFeedbackEnabled.collectAsStateWithLifecycle()

    // Derive dark theme from user preference
    val isSystemDark = isSystemInDarkTheme()
    val darkTheme =
        when (themeMode) {
            ThemeMode.SYSTEM -> isSystemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }

    ListenUpTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColorsEnabled,
    ) {
        ProvideHaptics(hapticFeedbackEnabled = hapticFeedbackEnabled) {
            // Paint the themed surface edge-to-edge (behind the system bars) so there are no
            // window-background bands at the top/bottom; inner scaffolds stay transparent over it.
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                ListenUpNavigation()
            }
        }
    }
}
