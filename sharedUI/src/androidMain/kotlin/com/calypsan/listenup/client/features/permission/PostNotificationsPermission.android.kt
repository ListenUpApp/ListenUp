package com.calypsan.listenup.client.features.permission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android actual for [RequestPostNotificationsPermission].
 *
 * [Manifest.permission.POST_NOTIFICATIONS] was introduced in API 33 (Android 13 /
 * TIRAMISU). Without a runtime grant the playback media notification is silently
 * suppressed; playback itself is unaffected.
 *
 * The request is launched at most once per session:
 * - [rememberSaveable] ensures the flag survives configuration changes, so a
 *   device rotation while the dialog is visible does not trigger a second prompt.
 * - The already-granted check skips the dialog when the user has previously
 *   accepted.
 *
 * On API 32 and below the permission does not exist and this composable is a no-op.
 */
@Composable
actual fun RequestPostNotificationsPermission() {
    // POST_NOTIFICATIONS was introduced in API 33 (TIRAMISU). This check is a static
    // device property — the API level never changes at runtime — so it is safe to
    // return early here without disturbing Compose hook ordering on API 33+ devices.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val permission = Manifest.permission.POST_NOTIFICATIONS

    // rememberSaveable must be called unconditionally (after the static SDK guard above).
    // It survives configuration changes so a rotation does not re-trigger the dialog.
    var alreadyAsked by rememberSaveable { mutableStateOf(false) }

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = {
                // Grant or deny — playback works either way; no action required here.
            },
        )

    // LaunchedEffect(Unit) runs once per composition session. The alreadyAsked guard
    // inside covers the re-entry case (e.g. after a configuration change LaunchedEffect
    // re-fires but rememberSaveable preserves the true value).
    LaunchedEffect(Unit) {
        if (!alreadyAsked &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        ) {
            alreadyAsked = true
            launcher.launch(permission)
        }
    }
}
