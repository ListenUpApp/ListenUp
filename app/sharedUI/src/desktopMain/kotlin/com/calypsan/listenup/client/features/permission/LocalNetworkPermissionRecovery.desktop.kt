package com.calypsan.listenup.client.features.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop actual for [rememberLocalNetworkPermissionRecovery]. JVM desktop has no local network
 * permission, so it is always granted and there is nothing to recover.
 */
@Composable
actual fun rememberLocalNetworkPermissionRecovery(): LocalNetworkPermissionRecovery =
    remember {
        LocalNetworkPermissionRecovery(
            isGranted = true,
            action = LocalNetworkRecoveryAction.RequestPermission,
            recover = {},
        )
    }
