package com.calypsan.listenup.client.features.permission

import androidx.compose.runtime.Composable

/**
 * Desktop actual for [RequestPostNotificationsPermission].
 *
 * The [android.Manifest.permission.POST_NOTIFICATIONS] concept does not apply to
 * JVM desktop targets. This composable is intentionally a no-op.
 */
@Composable
actual fun RequestPostNotificationsPermission() {
    // No permission model on desktop — nothing to do.
}
