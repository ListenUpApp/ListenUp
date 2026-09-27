package com.calypsan.listenup.client.features.shell.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.design.components.SignOutConfirmDialog

/**
 * The shell's single sign-out question.
 *
 * The shell offers sign-out in two places — the rail's Logout and the avatar menu's "Sign out" —
 * and neither signs out directly. Both [request] here, and [SignOutConfirmationHost] asks the one
 * question for the whole shell, so every path confirms the same way.
 */
@Stable
internal class SignOutConfirmation {
    /** Whether the question is showing. */
    var isAsking by mutableStateOf(false)
        private set

    /** Asks whether to sign out. Signing out happens only once the user confirms. */
    fun request() {
        isAsking = true
    }

    /** Withdraws the question, whether it was answered or dismissed. */
    fun withdraw() {
        isAsking = false
    }
}

/** Remembers the shell's [SignOutConfirmation]. */
@Composable
internal fun rememberSignOutConfirmation(): SignOutConfirmation = remember { SignOutConfirmation() }

/**
 * Shows [SignOutConfirmDialog] while [confirmation] is asking.
 *
 * @param onSignOut Signs out, once the user has confirmed.
 */
@Composable
internal fun SignOutConfirmationHost(
    confirmation: SignOutConfirmation,
    onSignOut: () -> Unit,
) {
    if (confirmation.isAsking) {
        SignOutConfirmDialog(
            onConfirm = {
                confirmation.withdraw()
                onSignOut()
            },
            onDismiss = confirmation::withdraw,
        )
    }
}
