package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsEvent
import com.calypsan.listenup.client.presentation.admin.upload.uploadSelectionRefusal
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.web.candidatesFrom
import com.calypsan.listenup.web.copyToClipboard
import com.calypsan.listenup.web.features.settings.AccountRouteContent
import com.calypsan.listenup.web.nav.ADMIN_KEY
import com.calypsan.listenup.web.nav.BACKUPS_KEY
import com.calypsan.listenup.web.nav.CATEGORIES_KEY
import com.calypsan.listenup.web.nav.COLLECTIONS_KEY
import com.calypsan.listenup.web.nav.IMPORTS_KEY
import com.calypsan.listenup.web.nav.INBOX_KEY
import com.calypsan.listenup.web.nav.INVITE_KEY
import com.calypsan.listenup.web.nav.LIBRARY_KEY
import com.calypsan.listenup.web.nav.NEW_KEY
import com.calypsan.listenup.web.nav.ORGANIZE_KEY
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.SETTINGS_KEY
import com.calypsan.listenup.web.nav.UPLOAD_KEY
import com.calypsan.listenup.web.nav.USER_KEY
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.shell.NotFoundPage
import com.calypsan.listenup.web.shell.nowMs
import com.calypsan.listenup.web.uploadRefusalSentence

/**
 * The `/admin` branch — the people on this server.
 *
 * The sidebar entry is already gated on `observeIsAdmin`, so this route is only reachable by an
 * admin through the UI. That is a convenience, not the guard: every action here is owner-gated
 * server-side, which is what actually stops a typed URL.
 */
@Composable
internal fun AdminRoute(
    openAdmin: OpenAdmin,
    onOpenLibrarySettings: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenServerSettings: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenCollections: () -> Unit,
    onOpenBackups: () -> Unit,
    onOpenImports: () -> Unit,
    onOpenInvite: () -> Unit,
    onOpenUpload: () -> Unit,
    onOpenOrganize: () -> Unit,
    onOpenUser: (String) -> Unit,
    landOnPending: Boolean,
) {
    val session = rememberSession { openAdmin() }
    val nowMs = remember { currentEpochMilliseconds() }

    AdminPage(
        state = session.state.collectAsState().value,
        nowMs = nowMs,
        onApproveUser = session.onApproveUser,
        onDenyUser = session.onDenyUser,
        onDeleteUser = session.onDeleteUser,
        onRevokeInvite = session.onRevokeInvite,
        onDecidePasswordReset = session.onDecidePasswordReset,
        onDismissResetCode = session.onDismissResetCode,
        onSetRegistrationPolicy = session.onSetRegistrationPolicy,
        onClearError = session.onClearError,
        onRetry = session.onRetry,
        onOpenLibrarySettings = onOpenLibrarySettings,
        onOpenInbox = onOpenInbox,
        onOpenServerSettings = onOpenServerSettings,
        onOpenCategories = onOpenCategories,
        onOpenCollections = onOpenCollections,
        onOpenBackups = onOpenBackups,
        onOpenImports = onOpenImports,
        onOpenInvite = onOpenInvite,
        onOpenUpload = onOpenUpload,
        onOpenOrganize = onOpenOrganize,
        onOpenUser = onOpenUser,
        landOnPending = landOnPending,
    )
}

/**
 * Everything under `/admin/…` that is not the Admin page itself.
 *
 * Split out of [AccountRouteContent] when the eighth admin surface pushed that function past the
 * branching the build allows. The line drawn here is the one the URLs already draw: every branch
 * below used to re-test `segments.firstOrNull() == ADMIN_KEY`, and testing it once in the caller is
 * what turns eight compound conditions into eight `==`.
 *
 * [sub] is the segment after `admin`; [id] the one after that, where a sub-path has one.
 */
@Composable
internal fun AdminRouteContent(
    sub: String,
    id: String?,
    router: Router,
    admin: AdminSessions,
    onToast: (String) -> Unit,
) {
    when (sub) {
        LIBRARY_KEY -> {
            LibrarySettingsRoute(router = router, openLibrarySettings = admin.librarySettings)
        }

        INBOX_KEY -> {
            AdminInboxRoute(router = router, openAdminInbox = admin.inbox)
        }

        SETTINGS_KEY -> {
            ServerSettingsRoute(router = router, openServerSettings = admin.serverSettings)
        }

        CATEGORIES_KEY -> {
            CategoriesRoute(router = router, openCategories = admin.categories)
        }

        // ⛔ The id case first, in both families below. Without it the bare list matches every URL
        // beneath it and the detail page becomes unreachable by link.
        COLLECTIONS_KEY -> {
            if (id != null) {
                CollectionDetailRoute(
                    router = router,
                    openCollectionDetail = admin.collectionDetail,
                    collectionId = id,
                    onToast = onToast,
                )
            } else {
                CollectionsRoute(router = router, openCollections = admin.collections, onToast = onToast)
            }
        }

        BACKUPS_KEY -> {
            if (id != null) {
                RestoreRoute(router = router, openRestore = admin.restore, backupId = id)
            } else {
                BackupsRoute(router = router, openBackups = admin.backups)
            }
        }

        // `/admin/imports/new` is the flow; the bare path is the list of past runs. The third
        // segment carries a literal here rather than an id, which is why it reads as a comparison.
        IMPORTS_KEY -> {
            if (id == NEW_KEY) {
                ImportFlowRoute(router = router, openImportFlow = admin.importFlow)
            } else {
                ImportsRoute(router = router, openImports = admin.imports)
            }
        }

        INVITE_KEY -> {
            CreateInviteRoute(router = router, openCreateInvite = admin.createInvite, onToast = onToast)
        }

        UPLOAD_KEY -> {
            UploadRoute(router = router, openUpload = admin.upload, onToast = onToast)
        }

        ORGANIZE_KEY -> {
            OrganizeRoute(router = router, openOrganize = admin.organize, onToast = onToast)
        }

        USER_KEY -> {
            if (id != null) {
                UserDetailRoute(router = router, openUserDetail = admin.userDetail, userId = id)
            } else {
                // `/admin/user` with nobody named is not a page — it is a link that lost its id.
                NotFoundPage(onGoHome = { router.navigate(Routes.home) })
            }
        }

        // An `/admin/*` path nobody routed. The shell's own not-found, not Admin — the same
        // resolution the `size <= 1` guard reaches for `/admin/nonsense`, stated here as the
        // absence of a branch rather than as a length test.
        else -> {
            NotFoundPage(onGoHome = { router.navigate(Routes.home) })
        }
    }
}

/**
 * Opens an upload session and collects it.
 *
 * ⛔ Nothing is read here: each picked file streams from disk as the upload sends it, on this
 * route's scope, so leaving the page stops the reading along with the session.
 */
@Composable
private fun UploadRoute(
    router: Router,
    openUpload: OpenUpload,
    onToast: (String) -> Unit,
) {
    val session = rememberSession { openUpload() }
    val scope = rememberCoroutineScope()

    UploadPage(
        state = session.state.collectAsState().value,
        onFilesPicked = { files ->
            if (files.isNotEmpty()) {
                val candidates = candidatesFrom(files, scope)
                val refusal = uploadSelectionRefusal(candidates)
                if (refusal != null) {
                    onToast(uploadRefusalSentence(refusal))
                } else {
                    session.onFilesPicked(candidates)
                }
            }
        },
        onCancel = session.onCancel,
        onReset = session.onReset,
        onOpenAdmin = { router.navigate(Routes.Admin.root) },
    )
}

/** Opens the file-organizer session and collects it, surfacing its one-shot events as toasts. */
@Composable
private fun OrganizeRoute(
    router: Router,
    openOrganize: OpenOrganize,
    onToast: (String) -> Unit,
) {
    val session = rememberSession { openOrganize() }

    // ⛔ A Channel, so re-collecting never replays a confirmation the reader already dismissed.
    LaunchedEffect(session) {
        session.events.collect { event ->
            onToast(
                when (event) {
                    OrganizeSettingsEvent.RulesSaved -> "Organization settings saved."
                    OrganizeSettingsEvent.AlreadyOrganized -> "Your library is already organized."
                },
            )
        }
    }

    OrganizePage(
        state = session.state.collectAsState().value,
        actions =
            OrganizeActions(
                onPreset = session.onPreset,
                onSeriesPrefix = session.onSeriesPrefix,
                onAuthorForm = session.onAuthorForm,
                onSaveRules = session.onSaveRules,
                onOrganize = session.onOrganize,
                onConfirmOrganize = session.onConfirmOrganize,
                onDismissPreview = session.onDismissPreview,
                onDismissReport = session.onDismissReport,
                onResume = session.onResume,
            ),
        onOpenAdmin = { router.navigate(Routes.Admin.root) },
    )
}

/** Opens a create-invite session and collects it. */
@Composable
private fun CreateInviteRoute(
    router: Router,
    openCreateInvite: OpenCreateInvite,
    onToast: (String) -> Unit,
) {
    val session = rememberSession { openCreateInvite() }

    CreateInvitePage(
        state = session.state.collectAsState().value,
        onCreate = session.onCreate,
        onClearError = session.onClearError,
        onCreateAnother = session.onReset,
        // ⛔ The toast reports what actually happened, not what was attempted. `navigator.clipboard`
        // is absent outside a secure context — which a LAN server over plain http is — and a page
        // that said "Copied!" either way would send an admin off to paste nothing.
        onCopy = { url ->
            copyToClipboard(url) { copied ->
                onToast(
                    if (copied) "Link copied." else "Couldn't reach the clipboard — select the link to copy it.",
                )
            }
        },
        onOpenAdmin = { router.navigate(Routes.Admin.root) },
    )
}

/** Opens one member's page and collects it. */
@Composable
private fun UserDetailRoute(
    router: Router,
    openUserDetail: OpenUserDetail,
    userId: String,
) {
    val session = rememberSession(userId) { openUserDetail(userId) }

    UserDetailPage(
        state = session.state.collectAsState().value,
        permissions = session.permissions.collectAsState().value,
        actions = session.actions,
        onOpenAdmin = { router.navigate(Routes.Admin.root) },
    )
}
