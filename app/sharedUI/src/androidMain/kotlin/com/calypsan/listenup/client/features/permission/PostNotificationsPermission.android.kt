package com.calypsan.listenup.client.features.permission

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android actual for [rememberPostNotificationsPermission].
 *
 * [Manifest.permission.POST_NOTIFICATIONS] arrived in API 33 and minSdk is 33, so this is *always*
 * a runtime grant — there is no version below which it is implicitly held. Without it,
 * `NotificationManagerCompat.notify` is a silent no-op: nothing throws, nothing logs, the
 * notification simply never appears.
 *
 * The request fires at most once per session ([rememberSaveable] survives a rotation while the
 * dialog is up), but the returned state is re-read on every composition entry, so a user who grants
 * the permission in system Settings and returns sees the caller update without a restart.
 */
@Composable
actual fun rememberPostNotificationsPermission(): Boolean {
    val context = LocalContext.current
    val permission = Manifest.permission.POST_NOTIFICATIONS

    fun currentlyGranted() =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(currentlyGranted()) }
    // Survives configuration changes so a rotation mid-dialog does not prompt twice.
    var alreadyAsked by rememberSaveable { mutableStateOf(false) }

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            // The result is the whole point now: a caller gating a "we'll notify you" promise
            // needs the answer, not merely the knowledge that it asked.
            onResult = { isGranted -> granted = isGranted },
        )

    LaunchedEffect(Unit) {
        // Re-read rather than trusting the initial snapshot: the permission may have been granted
        // in system Settings since this composable last ran.
        granted = currentlyGranted()
        if (!granted && !alreadyAsked) {
            alreadyAsked = true
            launcher.launch(permission)
        }
    }

    return granted
}
