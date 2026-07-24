package com.calypsan.listenup.client

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.calypsan.listenup.client.presentation.startup.AppStartupViewModel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.distinctUntilChanged
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
class MainActivity : ComponentActivity() {
    private val authSession: AuthSession by inject()
    private val syncRepository: SyncRepository by inject()
    private val localPreferences: LocalPreferences by inject()
    private val connectionCoordinator: ConnectionCoordinator by inject()
    private val deepLinkManager: DeepLinkManager by inject()
    private val shortcutActionManager: ShortcutActionManager by inject()
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
        // engine.start() is single-flight, so overlapping with onResume is safe.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                authSession.authState
                    .map { it is AuthState.Authenticated }
                    .distinctUntilChanged()
                    .collect { authenticated ->
                        if (authenticated) {
                            logger.debug { "Authenticated — connecting realtime sync" }
                            syncRepository.connectRealtime()
                        }
                    }
            }
        }

        setContent {
            PostureProvider {
                ListenUpApp()
            }
        }
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
                // instead of the previous silent fall-through to auth routing.
                logger.warn { "ACTION_VIEW did not decode to a share target: data=$raw" }
            }
        }

        // Check for shortcut actions
        when (intent.action) {
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

        // Connect realtime sync when app comes to foreground (if authenticated)
        lifecycleScope.launch {
            val isAuthenticated = authSession.getAccessToken() != null
            if (isAuthenticated) {
                logger.debug { "App resumed and user authenticated, connecting realtime sync" }
                syncRepository.connectRealtime()
            } else {
                logger.debug { "App resumed but user not authenticated, skipping realtime sync" }
            }
        }
    }

    override fun onPause() {
        super.onPause()

        // Record background timestamp so onAppForegrounded can decide
        // whether a library-setup re-check is needed on the next resume.
        appStartupViewModel.onAppBackgrounded()

        // Disconnect realtime sync when app goes to background to save battery
        logger.debug { "App paused, disconnecting realtime sync to save battery" }
        lifecycleScope.launch { syncRepository.disconnect() }
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
