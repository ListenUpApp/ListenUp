package com.calypsan.listenup.web.features.hardcover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.hardcover.HardcoverSyncProblem
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchEvent
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSyncStatus
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksEvent
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksUiState
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsEvent
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.web.design.ShowActionToast
import com.calypsan.listenup.web.design.ToastAction
import com.calypsan.listenup.web.design.ToastTone
import com.calypsan.listenup.web.features.notifications.NotificationPrefsRoute
import com.calypsan.listenup.web.nav.KEPT_OFF_KEY
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.shell.nowMs
import kotlinx.browser.window

/**
 * `/settings/hardcover` — connecting a Hardcover account; and `/settings/hardcover/kept-off`, the books
 * kept off Hardcover (#1541), served by the same session.
 *
 * A sub-route of Settings for the reason [NotificationPrefsRoute] is: the connection is a live
 * server stream with its own phases, and the Settings page stays a page of instant local choices.
 *
 * The ViewModel's automatic open of the approval page is honoured best-effort only. It arrives after
 * an awaited RPC, not inside the click, so a popup blocker may refuse it — which is why the page
 * always carries a real new-tab link to the same URL rather than depending on this.
 */
@Composable
internal fun HardcoverRoute(
    router: Router,
    openHardcover: OpenHardcover,
    onToast: (String) -> Unit,
    onActionToast: ShowActionToast,
    subPage: String?,
) {
    val session = rememberSession { openHardcover() }
    val state = session.state.collectAsState().value
    // A Sync now that failed is brief: a toast with Try again says it, while the sync line stays as
    // it was. Keyed on the failure itself, so it is said once each time it arrives.
    val syncNowFailed =
        (state as? HardcoverSettingsUiState.Connected)?.sync ==
            HardcoverSyncStatus.Problem(HardcoverSyncProblem.SYNC_NOW_FAILED)
    LaunchedEffect(syncNowFailed) {
        if (syncNowFailed) {
            onActionToast(SYNC_NOW_FAILED_NOTICE, ToastTone.Failure, ToastAction("Try again", session.onSyncNow))
        }
    }
    LaunchedEffect(session) {
        session.events.collect { event ->
            when (event) {
                is HardcoverSettingsEvent.OpenVerificationPage -> window.open(event.url, "_blank", "noopener")

                // `AppError.message` is a user-facing constant per subtype — printed, not reworded.
                is HardcoverSettingsEvent.ShowError -> onToast(event.error.message)
            }
        }
    }

    if (subPage == KEPT_OFF_KEY) {
        KeptOffRoute(router = router, session = session, onToast = onToast)
        return
    }

    HardcoverPage(
        state = state,
        onConnect = session.onConnect,
        onDisconnect = session.onDisconnect,
        onSyncNow = session.onSyncNow,
        onSetShareMode = session.onSetShareMode,
        onSendHistory = session.onSendHistory,
        onDismissHistory = session.onDismissHistory,
        onFindMatch = { bookId -> router.navigate(Routes.Book.hardcover(bookId)) },
        onOpenKeptOff = { router.navigate(Routes.settingsHardcoverKeptOff) },
        onOpenSettings = { router.navigate(Routes.settings) },
        nowMs = nowMs(),
    )
}

/**
 * `/settings/hardcover/kept-off` — the books kept off Hardcover (#1541), over the Hardcover screen's own
 * session. Each Sync again is said in a toast; after the last, or when reached with nothing kept off (a
 * stale link, or the last synced again on another device), it returns to the Hardcover page in place.
 */
@Composable
private fun KeptOffRoute(
    router: Router,
    session: HardcoverSession,
    onToast: (String) -> Unit,
) {
    val keptOff = session.keptOff.collectAsState().value
    val toHardcover = { router.replace(Routes.settingsHardcover) }
    LaunchedEffect(session) {
        session.keptOffEvents.collect { event ->
            when (event) {
                // en.json's `hardcover.syncing_again`.
                is KeptOffBooksEvent.SyncingAgain -> {
                    onToast("Syncing ${event.title} with Hardcover again")
                    if (event.wasLast) toHardcover()
                }

                // `AppError.message` is a user-facing constant per subtype — printed, not reworded.
                is KeptOffBooksEvent.ShowError -> {
                    onToast(event.error.message)
                }
            }
        }
    }
    LaunchedEffect(keptOff) {
        if (keptOff is KeptOffBooksUiState.Loaded && keptOff.books.isEmpty()) toHardcover()
    }
    KeptOffBooksPage(
        state = keptOff,
        onSyncAgain = session.onSyncAgain,
        onOpenSettings = { router.navigate(Routes.settings) },
        onOpenHardcover = { router.navigate(Routes.settingsHardcover) },
    )
}

/** en.json's `hardcover.sync_now_failed_notice`. */
private const val SYNC_NOW_FAILED_NOTICE = "Couldn't reach Hardcover. Nothing was lost."

/**
 * `/book/{id}/hardcover` — Find on Hardcover over one book. Not `/match`: that is Match details'.
 *
 * A pick or a removed match goes to the book, replacing this page in history so Back leaves the way
 * the reader came in; a pick says what it matched in a toast with Undo, which the session keeps
 * answering after the page has gone. An error is a toast in the words the error already carries.
 */
@Composable
internal fun HardcoverMatchRoute(
    router: Router,
    openHardcoverMatch: OpenHardcoverMatch,
    bookId: String,
    onToast: (String) -> Unit,
    onActionToast: ShowActionToast,
) {
    val session = rememberSession(bookId) { openHardcoverMatch(bookId) }
    LaunchedEffect(session) {
        session.events.collect { event ->
            when (event) {
                is HardcoverMatchEvent.Linked -> {
                    onActionToast(
                        linkedToastText(event.picked),
                        ToastTone.Notice,
                        ToastAction("Undo", session.onUndoLink),
                    )
                    router.replace(Routes.Book.detail(bookId))
                }

                HardcoverMatchEvent.MatchRemoved -> {
                    router.replace(Routes.Book.detail(bookId))
                }

                // `AppError.message` is a user-facing constant per subtype — printed, not reworded.
                is HardcoverMatchEvent.ShowError -> {
                    onToast(event.error.message)
                }
            }
        }
    }
    HardcoverMatchPage(
        state = session.state.collectAsState().value,
        onQueryChange = session.onQueryChange,
        onSearch = session.onSearch,
        onSearchFor = session.onSearchFor,
        onPick = session.onPick,
        onRemoveMatch = session.onRemoveMatch,
        onOpenLibrary = { router.navigate(Routes.library) },
        onOpenBook = { router.navigate(Routes.Book.detail(bookId)) },
    )
}
