package com.calypsan.listenup.client.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.error_book_not_connected
import listenup.composeapp.generated.resources.error_book_wrong_server
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.features.admin.AdminFocus
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.data.repository.DeepLinkManager
import com.calypsan.listenup.client.share.ShareResolution
import com.calypsan.listenup.client.share.ShareTarget
import com.calypsan.listenup.client.share.ShareTargetResolver
import com.calypsan.listenup.client.data.repository.ShortcutAction
import com.calypsan.listenup.client.data.repository.ShortcutActionManager
import io.github.oshai.kotlinlogging.KotlinLogging
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PlayerViewModel
import com.calypsan.listenup.client.features.shell.ShellDestination
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import com.calypsan.listenup.client.playback.SleepTimerMode
import org.koin.compose.koinInject
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.api.result.AppResult

private val logger = KotlinLogging.logger {}

/**
 * Resets navigation to the [Shell] root, clearing any detail screens on the way. No-op when the
 * shell is already the top of the back stack. Shared by shortcut routing and share-link routing so
 * "land the user on a known root before pushing a detail" has exactly one definition.
 */
private fun resetToShell(backStack: NavBackStack<NavKey>) {
    if (backStack.lastOrNull() != Shell) {
        backStack.clear()
        backStack.add(Shell)
    }
}

/**
 * Shows the Library tab for a shortcut that needs the shell on top, returning whether it did.
 *
 * While a screen holds unsaved work (an upload in flight, an edit not yet saved) the stack is left
 * alone: resetting it would discard that work, and the shortcut's effect can wait until the admin
 * is done.
 */
private fun showLibraryUnlessWorkHeld(
    backStack: NavBackStack<NavKey>,
    onSelectShellDestination: (ShellDestination) -> Unit,
    holdsUnsavedWork: Boolean,
): Boolean {
    if (holdsUnsavedWork) {
        logger.info { "Shortcut left the open screen alone: it holds unsaved work" }
        return false
    }
    resetToShell(backStack)
    onSelectShellDestination(ShellDestination.Library)
    return true
}

/**
 * Routes a launcher/shortcut [ShortcutAction] to the right playback or navigation effect.
 *
 * Extracted from [AuthenticatedNavigation] so the composable's cyclomatic complexity stays
 * within budget — this is plain control flow with no Compose surface of its own. [backStack]
 * mutations and [onSelectShellDestination] are invoked from the caller's `LaunchedEffect`
 * coroutine, preserving the original ordering and threading.
 */
internal suspend fun handleShortcutAction(
    action: ShortcutAction,
    homeRepository: HomeRepository,
    playback: ShortcutPlayback,
    backStack: NavBackStack<NavKey>,
    onSelectShellDestination: (ShellDestination) -> Unit,
    onOpenSearch: () -> Unit,
    holdsUnsavedWork: () -> Boolean,
) {
    val showLibrary: () -> Unit = { showLibraryUnlessWorkHeld(backStack, onSelectShellDestination, holdsUnsavedWork()) }
    when (action) {
        is ShortcutAction.Resume -> {
            // Get the most recent book and play it
            val result = homeRepository.getContinueListening(1)
            if (result is AppResult.Success && result.data.isNotEmpty()) {
                val book = result.data.first()
                logger.info { "Resuming book: ${book.title}" }
                playback.playBook(BookId(book.bookId))
                playback.expand()
            } else {
                logger.warn { "No recent book to resume" }
                showLibrary()
            }
        }

        is ShortcutAction.PlayBook -> {
            logger.info { "Playing book: ${action.bookId}" }
            playback.playBook(BookId(action.bookId))
            playback.expand()
        }

        is ShortcutAction.Search -> {
            playback.collapse()
            if (showLibraryUnlessWorkHeld(backStack, onSelectShellDestination, holdsUnsavedWork())) onOpenSearch()
        }

        is ShortcutAction.NavigateToBook,
        is ShortcutAction.NavigateToAbsImport,
        is ShortcutAction.NavigateToPendingApprovals,
        is ShortcutAction.NavigateToUserProfile,
        -> {
            logger.info { "Navigating for shortcut: $action" }
            playback.collapse()
            backStack.openFromOutside(Shell, action.screensAboveShell(), holdsUnsavedWork())
        }

        is ShortcutAction.SleepTimer -> {
            startSleepTimerFromShortcut(
                action = action,
                homeRepository = homeRepository,
                playback = playback,
                showLibrary = showLibrary,
            )
        }
    }
}

/** Minutes the launcher's "Set sleep timer" shortcut uses when it carries no preset of its own. */
private const val DEFAULT_SHORTCUT_SLEEP_MINUTES = 30

/** How long the sleep-timer shortcut waits for the book it started to become the player's current one. */
private val SHORTCUT_BOOK_LOAD_TIMEOUT = 10.seconds

/**
 * Sets the sleep timer the launcher's "Set sleep timer" shortcut promises, resuming the latest book
 * first when nothing is loaded.
 *
 * The timer is set only once the book is current: changing the player's book cancels a running
 * timer, so one set before the book loads would be cancelled a moment later.
 */
private suspend fun startSleepTimerFromShortcut(
    action: ShortcutAction.SleepTimer,
    homeRepository: HomeRepository,
    playback: ShortcutPlayback,
    showLibrary: () -> Unit,
) {
    val timer = SleepTimerMode.Duration(action.timerMinutes ?: DEFAULT_SHORTCUT_SLEEP_MINUTES)
    if (playback.activeBookId != null) {
        playback.setSleepTimer(timer)
        playback.expand()
        return
    }

    val latest = homeRepository.getContinueListening(1)
    val recent = if (latest is AppResult.Success) latest.data.firstOrNull() else null
    if (recent == null) {
        logger.warn { "No recent book to start a sleep timer on" }
        showLibrary()
        return
    }

    playback.playBook(BookId(recent.bookId))
    playback.expand()
    val current =
        withTimeoutOrNull(SHORTCUT_BOOK_LOAD_TIMEOUT) {
            playback.activeBookIds.first { it == recent.bookId }
        }
    if (current == null) {
        logger.warn { "Book ${recent.bookId} did not become current in time; no sleep timer set" }
        return
    }
    playback.setSleepTimer(timer)
}

/**
 * The screens a navigating [ShortcutAction] stacks on the shell, bottom first — empty for the
 * actions that play rather than navigate.
 *
 * The last hop of every notification tap, shade tap and launcher shortcut: the target mapping
 * before it (`toShortcutAction`) has its own specs, and this is the half that decides which screen
 * actually opens.
 */
internal fun ShortcutAction.screensAboveShell(): List<Route> =
    when (this) {
        is ShortcutAction.NavigateToBook -> listOf(BookDetail(bookId))

        is ShortcutAction.NavigateToAbsImport -> listOf(AdminBackups, ImportFlow)

        // Admin carries the pending list and its approve/deny controls — the point of the
        // notification is to make the decision reachable in one tap, so it opens on that list.
        is ShortcutAction.NavigateToPendingApprovals -> listOf(Admin(focus = AdminFocus.PENDING_REGISTRATIONS))

        is ShortcutAction.NavigateToUserProfile -> listOf(UserProfile(userId))

        ShortcutAction.Resume,
        is ShortcutAction.PlayBook,
        ShortcutAction.Search,
        is ShortcutAction.SleepTimer,
        -> emptyList()
    }

/**
 * Resolves a pending BOOK share-link target against the connected server. Invite targets are
 * handled at the top level; this ignores them.
 *
 * Extracted from [AuthenticatedNavigation] to keep the orchestrator within complexity budget.
 */
@Composable
internal fun ShareLinkEffect(
    serverConfig: ServerConfig,
    backStack: NavBackStack<NavKey>,
    snackbarHostState: SnackbarHostState,
    holdsUnsavedWork: () -> Boolean,
    collapseNowPlaying: () -> Unit,
    deepLinkManager: DeepLinkManager = koinInject(),
) {
    val pendingTarget by deepLinkManager.pendingTarget.collectAsStateWithLifecycle()
    val wrongServerMessage = stringResource(Res.string.error_book_wrong_server)
    val notConnectedMessage = stringResource(Res.string.error_book_not_connected)
    LaunchedEffect(pendingTarget) {
        val book = pendingTarget as? ShareTarget.Book ?: return@LaunchedEffect
        when (val resolution = ShareTargetResolver.resolve(book, serverConfig.getConnectedServerId())) {
            is ShareResolution.OpenBook -> {
                collapseNowPlaying()
                backStack.openFromOutside(Shell, listOf(BookDetail(resolution.bookId.value)), holdsUnsavedWork())
            }

            is ShareResolution.WrongServer -> {
                snackbarHostState.showSnackbar(wrongServerMessage)
            }

            is ShareResolution.NotConnected -> {
                snackbarHostState.showSnackbar(notConnectedMessage)
            }

            // OpenInviteClaim handled at top level; NoAccess not produced by the pure resolver
            // (an inaccessible book opens Book Detail, which shows its own empty state).
            else -> {}
        }
        // consumeTarget after showSnackbar (which suspends until dismissed) so the link stays live
        // until the user has actually seen the error.
        deepLinkManager.consumeTarget()
    }
}

/**
 * The app's root surface, answering the hardware-keyboard shortcuts ([ShellKeyboardShortcuts]) with
 * the player and the shell's search. The search requests the `/` shortcut makes reach the shell
 * through [LocalShellSearchRequests]. Not on TV, where the D-pad's arrows and centre key must only
 * move focus.
 */
@Composable
internal fun AppKeyboardShortcuts(
    nowPlayingViewModel: PlayerViewModel,
    backStack: NavBackStack<NavKey>,
    searchRequests: ShellSearchRequests,
    content: @Composable BoxScope.() -> Unit,
) {
    val playerState by nowPlayingViewModel.uiState.collectAsStateWithLifecycle()
    val playerExpanded by nowPlayingViewModel.expanded.collectAsStateWithLifecycle()
    ShellKeyboardShortcuts(
        enabled = !LocalDeviceContext.current.isLeanback,
        onShortcut = { shortcut ->
            answerShellShortcut(
                shortcut = shortcut,
                nowPlayingViewModel = nowPlayingViewModel,
                hasBook = playerState is PlayerUiState.Loaded,
                canOpenSearch = backStack.lastOrNull() == Shell && !playerExpanded,
                searchRequests = searchRequests,
            )
        },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    ) {
        CompositionLocalProvider(LocalShellSearchRequests provides searchRequests.requests) {
            content()
        }
    }
}

/**
 * Acts on a keyboard [shortcut], returning whether it did. The transport shortcuts need a loaded
 * book, and skip by the user's configured intervals (the view model's own [PlayerViewModel.skipBack]
 * and [PlayerViewModel.skipForward]). Search opens only where the shell's search is on screen.
 */
private fun answerShellShortcut(
    shortcut: ShellShortcut,
    nowPlayingViewModel: PlayerViewModel,
    hasBook: Boolean,
    canOpenSearch: Boolean,
    searchRequests: ShellSearchRequests,
): Boolean =
    when (shortcut) {
        ShellShortcut.PlayPause -> hasBook.also { if (it) nowPlayingViewModel.playPause() }
        ShellShortcut.SkipBack -> hasBook.also { if (it) nowPlayingViewModel.skipBack() }
        ShellShortcut.SkipForward -> hasBook.also { if (it) nowPlayingViewModel.skipForward() }
        ShellShortcut.OpenSearch -> canOpenSearch.also { if (it) searchRequests.request() }
    }

/**
 * Builds the dispatch lambda the notification inbox entries consume: every action routes through
 * [handleShortcutAction] — the SAME handler the shade's [ShortcutActionEffect] uses — so the two
 * entry points cannot disagree on where a notification lands.
 */
internal fun shortcutActionDispatcher(
    scope: CoroutineScope,
    homeRepository: HomeRepository,
    nowPlayingViewModel: PlayerViewModel,
    backStack: NavBackStack<NavKey>,
    onSelectShellDestination: (ShellDestination) -> Unit,
    onOpenSearch: () -> Unit,
    holdsUnsavedWork: () -> Boolean,
): (ShortcutAction) -> Unit =
    { action ->
        scope.launch {
            handleShortcutAction(
                action = action,
                homeRepository = homeRepository,
                playback = nowPlayingViewModel.asShortcutPlayback(),
                backStack = backStack,
                onSelectShellDestination = onSelectShellDestination,
                onOpenSearch = onOpenSearch,
                holdsUnsavedWork = holdsUnsavedWork,
            )
        }
    }

/**
 * Reads the pending [ShortcutAction] and routes it to the appropriate playback or navigation
 * effect via [handleShortcutAction].
 *
 * Extracted from [AuthenticatedNavigation] to keep the orchestrator within complexity budget.
 */
@Composable
internal fun ShortcutActionEffect(
    shortcutActionManager: ShortcutActionManager,
    homeRepository: HomeRepository,
    nowPlayingViewModel: PlayerViewModel,
    backStack: NavBackStack<NavKey>,
    onSelectShellDestination: (ShellDestination) -> Unit,
    onOpenSearch: () -> Unit,
    holdsUnsavedWork: () -> Boolean,
) {
    val pendingAction by shortcutActionManager.pendingAction.collectAsStateWithLifecycle()
    LaunchedEffect(pendingAction) {
        val action = pendingAction ?: return@LaunchedEffect

        logger.info { "Processing shortcut action: $action" }

        handleShortcutAction(
            action = action,
            homeRepository = homeRepository,
            playback = nowPlayingViewModel.asShortcutPlayback(),
            backStack = backStack,
            onSelectShellDestination = onSelectShellDestination,
            onOpenSearch = onOpenSearch,
            holdsUnsavedWork = holdsUnsavedWork,
        )

        // Consume the action after processing
        shortcutActionManager.consumeAction()
    }
}
