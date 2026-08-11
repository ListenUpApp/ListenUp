package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.mdns.InstanceIdentity
import com.calypsan.listenup.server.push.PushConfig
import com.calypsan.listenup.server.settings.ServerSettingsRepository

/**
 * Serves the pre-auth [ServerInfo] the client fetches on first connect.
 *
 * [ServerInfo.setupRequired] is derived, not stored: a fresh instance with no
 * users needs root setup. [ServerInfo.registrationPolicy] is read live from
 * [ServerSettingsRepository] so an admin's `setRegistrationPolicy` is reflected
 * on the next verification without a restart. [ServerInfo.instanceId] is the
 * stable, DB-persisted instance id (read-or-created via [InstanceIdentity], the
 * same id advertised over mDNS). [ServerInfo.pushEnabled] requires both the
 * admin toggle and a configured [PushConfig] relay. The remaining fields are
 * static server identity.
 */
class InstanceServiceImpl(
    private val sql: ListenUpDatabase,
    private val settings: ServerSettingsRepository,
    private val instanceIdentity: InstanceIdentity,
    private val pushConfig: PushConfig,
) : InstanceService {
    override suspend fun getServerInfo(): AppResult<ServerInfo> {
        // setupRequired is derived: a fresh instance with no users needs root setup.
        val setupRequired = suspendTransaction(sql) { !sql.usersQueries.hasAnyUser().executeAsOne() }
        return AppResult.Success(
            ServerInfo(
                name = settings.serverName(),
                version = ServerIdentity.VERSION,
                apiVersion = ServerIdentity.API_VERSION,
                setupRequired = setupRequired,
                registrationPolicy = settings.registrationPolicy(),
                remoteUrl = settings.remoteUrl(),
                instanceId = instanceIdentity.instanceId(),
                pushEnabled = settings.pushNotificationsEnabled() && pushConfig.configured,
            ),
        )
    }
}
