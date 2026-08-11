package com.calypsan.listenup.client.data.push

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * iOS [PushTokenProvider]: a process-local store for the APNs device token, filled by the
 * AppDelegate's `didRegisterForRemoteNotificationsWithDeviceToken` callback via
 * `KoinHelper.onPushTokenReceived`.
 *
 * APNs re-issues tokens at its own discretion (restore, reinstall, OS update) — every callback
 * overwrites the previous value. `null` until the first callback of this process: registration
 * still in flight, or notification authorization denied — which [PushRegistrar] treats as "no
 * push on this device", its normal no-op case.
 */
internal class ApnsTokenStore : PushTokenProvider {
    private val token = MutableStateFlow<String?>(null)

    /** Overwrites the stored token with the latest value APNs issued. */
    fun store(newToken: String) {
        token.value = newToken
    }

    override suspend fun currentToken(): String? = token.value
}
