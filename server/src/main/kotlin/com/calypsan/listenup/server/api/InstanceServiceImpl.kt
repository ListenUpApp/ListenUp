package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Serves the pre-auth [ServerInfo] the client fetches on first connect.
 *
 * [ServerInfo.setupRequired] is derived, not stored: a fresh instance with no
 * users needs root setup. [ServerInfo.registrationPolicy] is read live from
 * [ServerSettingsRepository] so an admin's `setRegistrationPolicy` is reflected
 * on the next verification without a restart. The remaining fields are static
 * server identity.
 */
class InstanceServiceImpl(
    private val db: Database,
    private val settings: ServerSettingsRepository,
) : InstanceService {
    override suspend fun getServerInfo(): AppResult<ServerInfo> {
        val setupRequired = suspendTransaction(db) { UserEntity.all().limit(1).empty() }
        return AppResult.Success(
            ServerInfo(
                name = settings.serverName(),
                version = ServerIdentity.VERSION,
                apiVersion = ServerIdentity.API_VERSION,
                setupRequired = setupRequired,
                registrationPolicy = settings.registrationPolicy(),
                remoteUrl = settings.remoteUrl(),
            ),
        )
    }
}
