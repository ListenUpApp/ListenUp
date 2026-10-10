package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsEvent
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.SETTINGS_KEY
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.shell.nowMs

/**
 * `/admin/collections` — the groups an admin curates.
 */
@Composable
internal fun CollectionsRoute(
    router: Router,
    openCollections: OpenCollections,
    onToast: (String) -> Unit,
) {
    val session = rememberSession { openCollections() }

    CollectionsPage(
        state = session.state.collectAsState().value,
        onCreate = session.onCreate,
        onDelete = session.onDelete,
        onClearError = session.onClearError,
        onOpenCollection = { id -> router.navigate(Routes.Admin.collection(id)) },
        onOpenAdmin = { router.navigate(Routes.Admin.root) },
        onCreateConfirmed = {
            onToast("Collection created.")
            session.onClearCreateSuccess()
        },
    )
}

/**
 * `/admin/collections/{id}` — one collection's books and the people who can see it.
 *
 * ⛔ `AdminCollectionDetailViewModel` takes the id as a CONSTRUCTOR parameter and builds its observe
 * pipeline around it in `init`, so a session cannot be repointed: the key on [collectionId] is what
 * makes a second collection a new session.
 */
@Composable
internal fun CollectionDetailRoute(
    router: Router,
    openCollectionDetail: OpenCollectionDetail,
    collectionId: String,
    onToast: (String) -> Unit,
) {
    val session = rememberSession(collectionId) { openCollectionDetail(collectionId) }

    CollectionDetailPage(
        state = session.state.collectAsState().value,
        onNameChange = session.onNameChange,
        onSaveName = session.onSaveName,
        onRemoveBook = session.onRemoveBook,
        onOpenAddBooks = session.onOpenAddBooks,
        onCloseAddBooks = session.onCloseAddBooks,
        onBookQuery = session.onBookQuery,
        onAddBook = session.onAddBook,
        onShowAddMember = session.onShowAddMember,
        onHideAddMember = session.onHideAddMember,
        onShare = session.onShare,
        onRevokeShare = session.onRevokeShare,
        onClearError = session.onClearError,
        onOpenCollections = { router.navigate(Routes.Admin.collections) },
        onSaveConfirmed = {
            onToast("Collection renamed.")
            session.onClearSaveSuccess()
        },
    )
}

/**
 * `/admin/categories` and `/settings/categories` — the genre tree, and what an admin or a curator may
 * do to it. [parentCrumb] and [parent] name the page it was opened from.
 *
 * Unkeyed and opened once for the route's life: the ViewModel observes the genre repository, so the
 * tree refreshes itself after every mutation rather than being re-fetched here.
 */
@Composable
internal fun CategoriesRoute(
    router: Router,
    openCategories: OpenCategories,
    parentCrumb: String = "Admin",
    parent: Route = Routes.Admin.root,
) {
    val session = rememberSession { openCategories() }

    CategoriesPage(
        state = session.state.collectAsState().value,
        onToggleExpanded = session.onToggleExpanded,
        onExpandAll = session.onExpandAll,
        onCollapseAll = session.onCollapseAll,
        onCreate = session.onCreate,
        onRename = session.onRename,
        onDelete = session.onDelete,
        onMove = session.onMove,
        onMerge = session.onMerge,
        onClearError = session.onClearError,
        onOpenAdmin = { router.navigate(parent) },
        mergeHistory = session.mergeHistory.collectAsState().value,
        mergeHistoryActions = session.mergeHistoryActions,
        parentCrumb = parentCrumb,
    )
}

/**
 * `/admin/settings` — what the server calls itself, and the two switches every listener feels.
 *
 * `SETTINGS_KEY` is reused as the second segment rather than given a name of its own: the URL reads
 * `/admin/settings`, and a listener's own `/settings` is a different first segment entirely, so the
 * two never collide.
 */
@Composable
internal fun ServerSettingsRoute(
    router: Router,
    openServerSettings: OpenServerSettings,
) {
    val session = rememberSession { openServerSettings() }

    ServerSettingsPage(
        state = session.state.collectAsState().value,
        onServerName = session.onServerName,
        onRemoteUrl = session.onRemoteUrl,
        onHoldNewBooks = session.onHoldNewBooks,
        onPushNotifications = session.onPushNotifications,
        onSetRatingSourceEnabled = session.onSetRatingSourceEnabled,
        onMetadataRegion = session.onMetadataRegion,
        onSaveHardcoverToken = session.onSaveHardcoverToken,
        onRemoveHardcoverToken = session.onRemoveHardcoverToken,
        onHardcoverMetadata = session.onHardcoverMetadata,
        onClearHardcoverTokenError = session.onClearHardcoverTokenError,
        onSave = session.onSave,
        onClearError = session.onClearError,
        onRetry = session.onRetry,
        onOpenAdmin = { router.navigate(Routes.Admin.root) },
        nowMs = nowMs(),
    )
}

/**
 * `/admin/inbox` — what the scanner brought in, and what it could not.
 *
 * Unkeyed and opened once for the route's life: the ViewModel loads both halves and subscribes to
 * the admin event stream from its own `init`, so a book that finishes scanning while the page is
 * open arrives on its own. Closing the session is what ends that subscription.
 */
@Composable
internal fun AdminInboxRoute(
    router: Router,
    openAdminInbox: OpenAdminInbox,
) {
    val session = rememberSession { openAdminInbox() }

    AdminInboxPage(
        state = session.state.collectAsState().value,
        onToggleBook = session.onToggleBook,
        onSelectAll = session.onSelectAll,
        onClearSelection = session.onClearSelection,
        onRelease = session.onRelease,
        onDismissIssue = session.onDismissIssue,
        onClearError = session.onClearError,
        onOpenBookEdit = { id -> router.navigate(Routes.Book.edit(id)) },
        onOpenMatch = { id -> router.navigate(Routes.Book.match(id)) },
        onOpenBook = { id -> router.navigate(Routes.Book.detail(id)) },
        onClearReleaseResult = session.onClearReleaseResult,
        onRetry = session.onRetry,
        onOpenAdmin = { router.navigate(Routes.Admin.root) },
    )
}

/**
 * `/admin/library` — which folders the library watches, after onboarding is over.
 *
 * `scanStarted` is held here rather than in the page because it is a one-shot: the ViewModel emits
 * `FolderSavedScanStarted` once, and a page re-reading it from state would either never show the
 * notice or never stop showing it. It clears when the browser is reopened, which is the next thing
 * anyone does after adding a folder.
 */
@Composable
internal fun LibrarySettingsRoute(
    router: Router,
    openLibrarySettings: OpenLibrarySettings,
) {
    val session = rememberSession { openLibrarySettings() }

    var scanStarted by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        session.events.collect { event ->
            if (event is LibrarySettingsEvent.FolderSavedScanStarted) scanStarted = true
        }
    }

    LibrarySettingsPage(
        state = session.state.collectAsState().value,
        scanStarted = scanStarted,
        onRemoveFolder = session.onRemoveFolder,
        onAddPath = session.onAddPath,
        onScan = session.onScan,
        onShowBrowser = { show ->
            if (show) scanStarted = false
            session.onShowBrowser(show)
        },
        onOpenBrowserPath = session.onOpenBrowserPath,
        onBrowserUp = session.onBrowserUp,
        onClearError = session.onClearError,
        onOpenAdmin = { router.navigate(Routes.Admin.root) },
    )
}
