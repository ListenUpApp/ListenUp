package com.calypsan.listenup.server.mdns

import com.calypsan.listenup.server.settings.ServerSettingsRepository
import java.util.UUID

/**
 * Read-or-create the server's stable instance id, persisted in the `server_settings` KV table
 * under [KEY]. The id is the mDNS TXT `id` record clients use to key (dedupe) discovered servers,
 * so it must survive restarts. Generated once on first read; stable thereafter.
 */
class InstanceIdentity(
    private val settings: ServerSettingsRepository,
) {
    suspend fun instanceId(): String =
        settings.getValue(KEY) ?: UUID.randomUUID().toString().also { settings.setValue(KEY, it) }

    private companion object {
        const val KEY = "instance_id"
    }
}
