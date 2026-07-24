package com.calypsan.listenup.client.features.permission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * API level at which [Manifest.permission.ACCESS_LOCAL_NETWORK] was introduced
 * (API 36, Android 16 "Baklava"). The permission becomes mandatory for mDNS /
 * multicast traffic once the app targets SDK 37 (Android 17).
 */
private const val ACCESS_LOCAL_NETWORK_MIN_API = 36

/**
 * Android actual for [RequestLocalNetworkPermission].
 *
 * [Manifest.permission.ACCESS_LOCAL_NETWORK] exists from API 36 (Android 16)
 * onward and is enforced for mDNS / multicast traffic once the app targets
 * SDK 37 (Android 17). This composable requests it once on first composition
 * and delivers the result to [onResult].
 *
 * On API 35 and below the permission does not exist and [onResult] is called
 * immediately with `true`.
 */
@Composable
actual fun RequestLocalNetworkPermission(onResult: (granted: Boolean) -> Unit) {
    val context = LocalContext.current

    // ACCESS_LOCAL_NETWORK was introduced in API 36 (Android 16). On earlier
    // API levels the permission doesn't exist and discovery works without it.
    if (Build.VERSION.SDK_INT < ACCESS_LOCAL_NETWORK_MIN_API) {
        LaunchedEffect(Unit) { onResult(true) }
        return
    }

    val permission = Manifest.permission.ACCESS_LOCAL_NETWORK

    // Already granted — skip the dialog.
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
        LaunchedEffect(Unit) { onResult(true) }
        return
    }

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { granted -> onResult(granted) },
        )

    LaunchedEffect(Unit) { launcher.launch(permission) }
}
