package com.calypsan.listenup.client.features.admin.backup

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_backup_download_failed
import listenup.composeapp.generated.resources.admin_backup_downloaded
import org.jetbrains.compose.resources.stringResource

/**
 * How a backup download ended, told in the app's snackbar — above the mini-player with the rest of
 * the app's feedback — rather than a Toast, which sits outside the app's own surface.
 */
@Stable
internal class BackupDownloadNotices(
    private val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    private val savedMessage: String,
    private val failedMessage: String,
) {
    /** The backup finished streaming to the device. */
    fun saved() {
        scope.launch { snackbar.showSnackbar(savedMessage) }
    }

    /** The backup could not be written where the listener chose. */
    fun failed() {
        scope.launch { snackbar.showSnackbar(failedMessage) }
    }
}

/** [BackupDownloadNotices] bound to the app-wide [LocalSnackbarHostState]. */
@Composable
internal fun rememberBackupDownloadNotices(): BackupDownloadNotices {
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val saved = stringResource(Res.string.admin_backup_downloaded)
    val failed = stringResource(Res.string.admin_backup_download_failed)
    return remember(snackbar, scope, saved, failed) {
        BackupDownloadNotices(snackbar = snackbar, scope = scope, savedMessage = saved, failedMessage = failed)
    }
}
