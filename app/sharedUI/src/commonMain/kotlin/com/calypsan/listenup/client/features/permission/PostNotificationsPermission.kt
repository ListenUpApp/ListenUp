package com.calypsan.listenup.client.features.permission

import androidx.compose.runtime.Composable

/**
 * Requests notification permission once per session and reports whether this device can actually
 * show a notification right now.
 *
 * Returns state rather than `Unit` because callers need to know the answer, not just that the
 * question was asked. `PendingApprovalScreen` is the case that forced it: it told a waiting
 * registrant "we'll notify you when you're approved" on the strength of having registered a push
 * watch token — but a token registers without any permission, so on Android 13+ the promise was
 * made to everyone and kept for nobody. The push arrived and `notify()` was a silent no-op. A
 * screen can only promise what the OS will let it deliver, so it has to ask the OS.
 *
 * - **Android (API 33+):** launches the system dialog via `rememberLauncherForActivityResult` and
 *   returns the live grant state, updating when the dialog resolves — from 33 on
 *   `POST_NOTIFICATIONS` is a runtime grant, never implicitly held.
 * - **Android 11–12 (API 30–32):** the permission does not exist; notifications are allowed, so
 *   this returns `true` without asking.
 * - **Desktop:** no permission model; always `true`.
 *
 * Fire-and-forget: never blocks navigation, never requires a grant.
 */
@Composable
expect fun rememberPostNotificationsPermission(): Boolean
