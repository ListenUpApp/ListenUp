package com.calypsan.listenup.client.features.permission

import androidx.compose.runtime.Composable

/**
 * Desktop actual for [rememberPostNotificationsPermission].
 *
 * There is no runtime permission model on JVM desktop, so nothing is requested and the answer is
 * unconditionally `true` — a caller gating a promise on this can make it honestly here.
 */
@Composable
actual fun rememberPostNotificationsPermission(): Boolean = true
