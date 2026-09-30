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
import com.calypsan.listenup.client.data.repository.LocalNetworkPermissionApi

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

    // Asks from DECLARED (36), not ENFORCED (37), deliberately: Android 16 already has the
    // permission and its prompt, so asking there means an upgrade to 17 finds it granted. Below 36
    // the permission doesn't exist and discovery works without it.
    if (Build.VERSION.SDK_INT < LocalNetworkPermissionApi.DECLARED) {
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
