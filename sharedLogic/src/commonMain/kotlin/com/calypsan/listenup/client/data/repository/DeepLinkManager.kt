package com.calypsan.listenup.client.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents a pending invite deep link that needs to be processed.
 *
 * @property serverUrl The full server URL (e.g., "https://audiobooks.example.com")
 * @property code The invite code from the URL
 */
data class InviteDeepLink(
    val serverUrl: String,
    val code: String,
)

/**
 * Manages pending deep link state for the application.
 *
 * When the app is opened via a deep link (e.g., invite URL), the parsed
 * data is stored here and consumed by the navigation layer.
 *
 * Flow:
 * 1. App receives intent with invite URL
 * 2. DeepLinkParser extracts server URL and invite code
 * 3. DeepLinkManager stores the pending invite
 * 4. Navigation layer observes pendingInvite and routes to InviteRegistration
 * 5. After processing, consumeInvite() clears the pending state
 *
 * Thread-safe via StateFlow - can be observed from any coroutine context.
 */
class DeepLinkManager {
    /**
     * Observable flow of pending invite deep link.
     * Null when no invite is pending.
     */
    val pendingInvite: StateFlow<InviteDeepLink?>
        field = MutableStateFlow<InviteDeepLink?>(null)

    /**
     * Sets a pending invite link to be processed by navigation.
     *
     * Called when the app receives an invite deep link intent.
     * Navigation layer will observe this and route to the registration flow.
     *
     * @param serverUrl The full server URL (including scheme)
     * @param code The invite code
     */
    fun setInviteLink(
        serverUrl: String,
        code: String,
    ) {
        pendingInvite.value = InviteDeepLink(serverUrl, code)
    }

    /**
     * Clears the pending invite after it has been processed.
     *
     * Should be called after:
     * - Successful registration completion
     * - User cancels the registration flow
     * - Invite is determined to be invalid
     */
    fun consumeInvite() {
        pendingInvite.value = null
    }

    /**
     * Checks if there's a pending invite without consuming it.
     */
    fun hasPendingInvite(): Boolean = pendingInvite.value != null
}
