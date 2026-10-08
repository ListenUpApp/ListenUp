package com.calypsan.listenup.client.features.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/** What the user can do, from inside the app, about a denied local network permission. */
enum class LocalNetworkRecoveryAction {
    /** The system will still show its permission dialog: ask again. */
    RequestPermission,

    /** The system will not ask again: only the app's page in Settings can grant it now. */
    OpenSettings,
}

/**
 * The local network permission as the connect screens need it: whether it is granted right now,
 * and the one action that can grant it.
 *
 * @property isGranted True when nothing is blocking the local network — the permission is held,
 *   or the platform does not enforce one.
 * @property action Which of [LocalNetworkRecoveryAction] [recover] will take.
 * @property recover Takes [action]: shows the permission dialog, or opens the app's Settings page.
 */
@Immutable
data class LocalNetworkPermissionRecovery(
    val isGranted: Boolean,
    val action: LocalNetworkRecoveryAction,
    val recover: () -> Unit,
)

/**
 * Tracks the local network permission (Android 17's `ACCESS_LOCAL_NETWORK`) and offers the way
 * back from a denial.
 *
 * Unlike [RequestLocalNetworkPermission] this never asks on its own; it answers "is it granted?"
 * and "what would fix it?", and re-reads both every time the screen resumes — so returning from
 * the permission dialog or from Settings updates the caller without a restart, and the caller can
 * re-run what the denial blocked.
 *
 * - **Android (API 37+):** [LocalNetworkPermissionRecovery.action] is
 *   [LocalNetworkRecoveryAction.RequestPermission] while the system would still show the dialog
 *   (`shouldShowRequestPermissionRationale`), and [LocalNetworkRecoveryAction.OpenSettings] once it
 *   will not. That reading is sound because manual entry is only ever reached from server
 *   selection, which always asks first — so "not granted and no rationale" means "denied for good",
 *   never "not asked yet". Below API 37 nothing is enforced and this always reads granted.
 * - **Desktop:** no permission model; always granted.
 */
@Composable
expect fun rememberLocalNetworkPermissionRecovery(): LocalNetworkPermissionRecovery
