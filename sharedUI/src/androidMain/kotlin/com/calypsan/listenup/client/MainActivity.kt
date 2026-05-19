package com.calypsan.listenup.client

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.data.repository.DeepLinkManager
import com.calypsan.listenup.client.data.repository.ShortcutAction
import com.calypsan.listenup.client.data.repository.ShortcutActionManager
import com.calypsan.listenup.client.deeplink.DeepLinkParser
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.domain.model.Instance
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.domain.usecase.GetInstanceUseCase
import com.calypsan.listenup.client.navigation.ListenUpNavigation
import com.calypsan.listenup.client.shortcuts.ShortcutActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.calypsan.listenup.client.presentation.startup.AppStartupViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.client.foldable.PostureProvider

/**
 * Main activity for the ListenUp app.
 *
 * Manages realtime sync lifecycle:
 * - Connects realtime sync when app comes to foreground (if authenticated)
 * - Disconnects realtime sync when app goes to background (saves battery)
 * - Auto-reconnects on app resume
 *
 * Handles deep links for invite URLs:
 * - listenup://join?server=...&code=... (custom scheme)
 *
 * This ensures real-time updates when actively using the app
 * while preserving battery life in the background.
 */
class MainActivity : ComponentActivity() {
    private val authSession: AuthSession by inject()
    private val syncRepository: SyncRepository by inject()
    private val syncEngine: SyncEngine by inject()
    private val localPreferences: LocalPreferences by inject()
    private val serverConfig: ServerConfig by inject()
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
     * - Invite deep links (listenup://join)
     * - App shortcut actions (RESUME, PLAY_BOOK, SEARCH, SLEEP_TIMER)
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // Check for deep link first
        DeepLinkParser.parse(intent)?.let { inviteLink ->
            println("MainActivity: Received invite deep link - server=${inviteLink.serverUrl}, code=${inviteLink.code}")
            deepLinkManager.setInviteLink(inviteLink.serverUrl, inviteLink.code)
            return
        }

        // Check for book deep link: listenup://book/{bookId}
        DeepLinkParser.parseBookDeepLink(intent)?.let { bookLink ->
            println("MainActivity: Received book deep link - bookId=${'$'}{bookLink.bookId}")
            shortcutActionManager.setPendingAction(ShortcutAction.NavigateToBook(bookLink.bookId))
            return
        }

        // Check for shortcut actions
        when (intent.action) {
            ShortcutActions.RESUME -> {
                println("MainActivity: Received RESUME shortcut action")
                shortcutActionManager.setPendingAction(ShortcutAction.Resume)
            }

            ShortcutActions.PLAY_BOOK -> {
                val bookId = intent.getStringExtra(ShortcutActions.EXTRA_BOOK_ID)
                if (bookId != null) {
                    println("MainActivity: Received PLAY_BOOK shortcut action - bookId=$bookId")
                    shortcutActionManager.setPendingAction(ShortcutAction.PlayBook(bookId))
                } else {
                    println("MainActivity: PLAY_BOOK action missing book_id extra")
                }
            }

            ShortcutActions.SEARCH -> {
                println("MainActivity: Received SEARCH shortcut action")
                shortcutActionManager.setPendingAction(ShortcutAction.Search)
            }

            ShortcutActions.SLEEP_TIMER -> {
                val timerMinutes =
                    intent
                        .getIntExtra(ShortcutActions.EXTRA_TIMER_MINUTES, -1)
                        .takeIf { it > 0 }
                println("MainActivity: Received SLEEP_TIMER shortcut action - minutes=$timerMinutes")
                shortcutActionManager.setPendingAction(ShortcutAction.SleepTimer(timerMinutes))
            }

            ShortcutActions.NAVIGATE_TO_ABS_IMPORT -> {
                val importId = intent.getStringExtra(ShortcutActions.EXTRA_IMPORT_ID)
                if (importId != null) {
                    println("MainActivity: Received NAVIGATE_TO_ABS_IMPORT action - importId=$importId")
                    shortcutActionManager.setPendingAction(ShortcutAction.NavigateToAbsImport(importId))
                } else {
                    println("MainActivity: NAVIGATE_TO_ABS_IMPORT action missing import_id extra")
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

        // Prefer local URL when app comes to foreground
        lifecycleScope.launch {
            serverConfig.preferLocalUrl()
        }

        // Connect realtime sync when app comes to foreground (if authenticated)
        lifecycleScope.launch {
            val isAuthenticated = authSession.getAccessToken() != null
            if (isAuthenticated) {
                println("MainActivity: App resumed and user authenticated, connecting realtime sync...")
                syncRepository.connectRealtime()
            } else {
                println("MainActivity: App resumed but user not authenticated, skipping realtime sync")
            }
        }
    }

    override fun onPause() {
        super.onPause()

        // Record background timestamp so onAppForegrounded can decide
        // whether a library-setup re-check is needed on the next resume.
        appStartupViewModel.onAppBackgrounded()

        // Disconnect realtime sync when app goes to background to save battery
        println("MainActivity: App paused, disconnecting realtime sync to save battery...")
        syncEngine.stop()
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
        ListenUpNavigation()
    }
}

/**
 * Screen that displays server instance information.
 */
@Composable
fun InstanceScreen(viewModel: InstanceViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.isLoading -> {
                ListenUpLoadingIndicator()
            }

            state.error != null -> {
                ErrorContent(
                    error = state.error!!,
                    onRetry = { viewModel.loadInstance() },
                )
            }

            state.instance != null -> {
                InstanceContent(instance = state.instance!!)
            }
        }
    }
}

@Composable
fun InstanceContent(instance: Instance) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "ListenUp Server",
                style = MaterialTheme.typography.headlineMedium,
            )

            HorizontalDivider()

            InfoRow(label = "Instance ID", value = instance.id.value)

            InfoRow(
                label = "Status",
                value = if (instance.isReady) "Ready" else "Needs Setup",
            )

            InfoRow(
                label = "Has Root User",
                value = if (instance.hasRootUser) "Yes" else "No",
            )

            if (instance.needsSetup) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                ) {
                    Text(
                        text = "⚠️ Server needs initial setup",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun ErrorContent(
    error: String,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Error",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

/**
 * UI state for the instance screen.
 */
data class InstanceUiState(
    val isLoading: Boolean = true,
    val instance: Instance? = null,
    val error: String? = null,
)

/**
 * ViewModel for managing instance data and state.
 *
 * Follows modern Android architecture with StateFlow for reactive UI updates.
 */
class InstanceViewModel(
    private val getInstanceUseCase: GetInstanceUseCase,
) : ViewModel() {
    val state: StateFlow<InstanceUiState>
        field = MutableStateFlow(InstanceUiState())

    init {
        loadInstance()
    }

    fun loadInstance() {
        viewModelScope.launch {
            state.value = InstanceUiState(isLoading = true)

            when (val result = getInstanceUseCase()) {
                is Success -> {
                    state.value =
                        InstanceUiState(
                            isLoading = false,
                            instance = result.data,
                        )
                }

                is Failure -> {
                    state.value =
                        InstanceUiState(
                            isLoading = false,
                            error = result.message,
                        )
                }
            }
        }
    }
}
