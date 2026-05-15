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

/** First API level that requires [Manifest.permission.ACCESS_LOCAL_NETWORK]. */
private const val API_LEVEL_ACCESS_LOCAL_NETWORK_REQUIRED = 36

/**
 * Android actual for [RequestLocalNetworkPermission].
 *
 * On API 36+ (Android 17), [Manifest.permission.ACCESS_LOCAL_NETWORK] is
 * required for mDNS / multicast traffic. This composable requests it once
 * on first composition and delivers the result to [onResult].
 *
 * On API 35 and below the permission does not exist and [onResult] is called
 * immediately with `true`.
 */
@Composable
actual fun RequestLocalNetworkPermission(onResult: (granted: Boolean) -> Unit) {
    val context = LocalContext.current

    // ACCESS_LOCAL_NETWORK was introduced in Android 17 (API 36).
    // On earlier API levels the permission doesn't exist and discovery
    // works without it.
    if (Build.VERSION.SDK_INT < API_LEVEL_ACCESS_LOCAL_NETWORK_REQUIRED) {
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
