package com.calypsan.listenup.server.mdns

import com.calypsan.listenup.server.settings.ServerSettingsRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Read-or-create the server's stable instance id, persisted in the `server_settings` KV table
 * under [KEY]. The id is the mDNS TXT `id` record clients use to key (dedupe) discovered servers,
 * so it must survive restarts. Generated once on first read; stable thereafter.
 */
class InstanceIdentity(
    private val settings: ServerSettingsRepository,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend fun instanceId(): String =
        settings.getValue(KEY) ?: Uuid.random().toString().also { settings.setValue(KEY, it) }

    private companion object {
        const val KEY = "instance_id"
    }
}
