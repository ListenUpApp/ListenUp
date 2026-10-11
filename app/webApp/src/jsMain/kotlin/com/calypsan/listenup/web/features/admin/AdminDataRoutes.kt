package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.core.BlobFileSource
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.web.BufferingSink
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.saveToDisk

/**
 * `/admin/imports` — what has been brought over before.
 */
@Composable
internal fun ImportsRoute(
    router: Router,
    openImports: OpenImports,
) {
    val session = rememberSession { openImports() }

    ImportsPage(
        state = session.state.collectAsState().value,
        onDelete = { summary -> session.onDelete(summary.id) },
        onClearError = session.onClearError,
        onRetry = session.onRetry,
        onNewImport = { router.navigate(Routes.Admin.newImport) },
        onOpenAdmin = { router.navigate(Routes.Admin.root) },
    )
}

/**
 * `/admin/imports/new` — one import run.
 *
 * The picked file is handed on as a [BlobFileSource], never read: the browser streams it from disk
 * as it uploads, the same bridge the backup upload uses.
 */
@Composable
internal fun ImportFlowRoute(
    router: Router,
    openImportFlow: OpenImportFlow,
) {
    val session = rememberSession { openImportFlow() }

    ImportFlowPage(
        state = session.state.collectAsState().value,
        onStart = { file -> session.onStart(BlobFileSource(file)) },
        onMapUser = { match, userId -> session.onMapUser(match.absUserId, userId) },
        onSkipUser = { match -> session.onSkipUser(match.absUserId) },
        onOpenBookSearch = session.onOpenBookSearch,
        onCloseBookSearch = session.onCloseBookSearch,
        onBookSearchQuery = session.onBookSearchQuery,
        onSelectBook = session.onSelectBook,
        onSkipBook = session.onSkipBook,
        onApply = session.onApply,
        onReset = session.onReset,
        onOpenImports = { router.navigate(Routes.Admin.imports) },
    )
}

/**
 * `/admin/backups` — take one, take one away, take one off the server, or put one back.
 *
 * ⛔ **This route is where the browser's two file seams live**, and neither belongs in the page.
 * A download writes into a [BufferingSink] and reaches the browser only when the ViewModel says the
 * transfer succeeded — the ViewModel closes the sink in a `finally`, before it knows, so saving on
 * close would deliver a truncated archive named as though it were whole. An upload hands the picked
 * file on as a [BlobFileSource], never read, so the browser streams it from disk.
 */
@Composable
internal fun BackupsRoute(
    router: Router,
    openBackups: OpenBackups,
) {
    val session = rememberSession { openBackups() }

    // The sink and the name it will be saved under, held between starting a download and hearing
    // that it finished. Null whenever no download is in flight.
    var pending by remember { mutableStateOf<Pair<String, BufferingSink>?>(null) }

    LaunchedEffect(session) {
        session.downloadSaved.collect {
            pending?.let { (filename, sink) -> saveToDisk(filename, sink.bytes()) }
            pending = null
        }
    }
    LaunchedEffect(session) {
        session.uploaded.collect { backupId ->
            router.navigate(Routes.Admin.backup(backupId.value))
        }
    }

    BackupsPage(
        state = session.state.collectAsState().value,
        uploadState = session.uploadState.collectAsState().value,
        onCreate = session.onCreate,
        onDownload = { backup ->
            val sink = BufferingSink()
            pending = backupFilename(backup.createdAt) to sink
            session.onDownload(BackupId(backup.id), sink)
        },
        onAskDelete = session.onAskDelete,
        onDismissDelete = session.onDismissDelete,
        onDelete = session.onDelete,
        onPickFile = { file -> session.onPickFile(BlobFileSource(file)) },
        onResetUpload = session.onResetUpload,
        onClearError = session.onClearError,
        onRetry = session.onRetry,
        onRestore = { backup -> router.navigate(Routes.Admin.backup(backup.id)) },
        onOpenAdmin = { router.navigate(Routes.Admin.root) },
    )
}

/** What a downloaded archive is called on the reader's machine. */
private fun backupFilename(createdAt: Timestamp): String =
    "listenup-" + formatWhen(createdAt.epochMillis).replace(Regex("[^0-9A-Za-z]+"), "-").trim('-') + ".listenup.zip"

/**
 * `/admin/backups/{id}` — the restore confirmation and the narration.
 *
 * Keyed on [backupId] for the same reason a collection's detail is: `RestoreBackupViewModel` takes
 * the id as a constructor parameter, so a session cannot be repointed.
 */
@Composable
internal fun RestoreRoute(
    router: Router,
    openRestore: OpenRestore,
    backupId: String,
) {
    val session = rememberSession(backupId) { openRestore(backupId) }

    RestorePage(
        state = session.state.collectAsState().value,
        progress = session.progress.collectAsState().value,
        onRequest = session.onRequest,
        onCancel = session.onCancel,
        onConfirm = session.onConfirm,
        onOpenBackups = { router.navigate(Routes.Admin.backups) },
    )
}
