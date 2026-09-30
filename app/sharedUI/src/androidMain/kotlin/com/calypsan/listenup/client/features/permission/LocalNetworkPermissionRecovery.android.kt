package com.calypsan.listenup.client.features.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.calypsan.listenup.client.data.repository.LocalNetworkPermissionApi

/**
 * Android actual for [rememberLocalNetworkPermissionRecovery].
 *
 * Grant state and "would the system still ask?" are re-read in [LifecycleResumeEffect], which
 * fires on return from both the permission dialog (its own activity) and the Settings app.
 */
@Composable
actual fun rememberLocalNetworkPermissionRecovery(): LocalNetworkPermissionRecovery {
    // Below ENFORCED nothing is blocked, so there the permission is never "denied".
    if (Build.VERSION.SDK_INT < LocalNetworkPermissionApi.ENFORCED) {
        return remember {
            LocalNetworkPermissionRecovery(
                isGranted = true,
                action = LocalNetworkRecoveryAction.RequestPermission,
                recover = {},
            )
        }
    }

    val context = LocalContext.current
    val activity = LocalActivity.current
    val permission = Manifest.permission.ACCESS_LOCAL_NETWORK

    fun readGranted() = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun readCanAsk() = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: false

    var granted by remember { mutableStateOf(readGranted()) }
    var canAsk by remember { mutableStateOf(readCanAsk()) }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            granted = isGranted
            canAsk = readCanAsk()
        }

    LifecycleResumeEffect(Unit) {
        granted = readGranted()
        canAsk = readCanAsk()
        onPauseOrDispose { }
    }

    val action = if (canAsk) LocalNetworkRecoveryAction.RequestPermission else LocalNetworkRecoveryAction.OpenSettings
    return LocalNetworkPermissionRecovery(
        isGranted = granted,
        action = action,
        recover = {
            when (action) {
                LocalNetworkRecoveryAction.RequestPermission -> launcher.launch(permission)
                LocalNetworkRecoveryAction.OpenSettings -> openAppSettings(activity, context)
            }
        },
    )
}

/** Opens this app's page in system Settings, where Permissions → Nearby devices lives. */
private fun openAppSettings(
    activity: Activity?,
    context: Context,
) {
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    if (activity != null) {
        activity.startActivity(intent)
    } else {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
