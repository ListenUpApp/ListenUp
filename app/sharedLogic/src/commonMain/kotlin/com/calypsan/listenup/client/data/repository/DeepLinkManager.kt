package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.share.ShareTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages pending share-link and deep-link state for the application.
 *
 * When the app is opened via a share link, [com.calypsan.listenup.client.share.ShareLinkCodec]
 * decodes the incoming URI into a [com.calypsan.listenup.client.share.ShareTarget] and stores it
 * here via [setPendingTarget]. Both platforms' routers observe [pendingTarget], resolve it
 * through [com.calypsan.listenup.client.share.ShareTargetResolver], and navigate accordingly.
 *
 * Thread-safe via StateFlow — can be observed from any coroutine context.
 */
class DeepLinkManager {
    /**
     * Observable flow of the pending share-link / deep-link target.
     *
     * Both platforms' routers observe this, run it through
     * [com.calypsan.listenup.client.share.ShareTargetResolver], and act on the resolution.
     * Null when nothing is pending.
     */
    val pendingTarget: StateFlow<ShareTarget?>
        field = MutableStateFlow<ShareTarget?>(null)

    /**
     * Stores a pending target to be processed by navigation. Called by the reception layer
     * after [com.calypsan.listenup.client.share.ShareLinkCodec.decode] yields a target.
     *
     * @param target The decoded [ShareTarget] to hold until navigation consumes it.
     */
    fun setPendingTarget(target: ShareTarget) {
        pendingTarget.value = target
    }

    /** Clears the pending target after navigation has consumed it (single-fire semantics). */
    fun consumeTarget() {
        pendingTarget.value = null
    }

    /** Checks whether a target is pending without consuming it. */
    fun hasPendingTarget(): Boolean = pendingTarget.value != null
}
