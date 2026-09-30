package com.calypsan.listenup.client.data.repository

/**
 * The Android API levels that matter for `ACCESS_LOCAL_NETWORK`, shared by the gate check here
 * and the permission UI in `:app:sharedUI` so the two can never disagree.
 */
object LocalNetworkPermissionApi {
    /**
     * API 36 (Android 16) is the first that declares the permission, so the system has a prompt to
     * show. Asking from here means a device that later upgrades to Android 17 already holds it.
     */
    const val DECLARED = 36

    /** API 37 (Android 17) is the first that blocks local-network traffic without the permission. */
    const val ENFORCED = 37
}
