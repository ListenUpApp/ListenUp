package com.calypsan.listenup.client.device

import com.calypsan.listenup.api.dto.auth.DeviceInfo

/**
 * Single source of the running device's structured [DeviceInfo]. Consumed by the auth
 * login flow (sent to the server as the session's device metadata) and by
 * [com.calypsan.listenup.client.playback.ListeningEventRecorder] (which derives its
 * device_label from it). Distinct from [DeviceContextProvider], which classifies the
 * device *type* for layout — this carries the device's *identity*.
 */
fun interface DeviceInfoProvider {
    fun current(): DeviceInfo
}
