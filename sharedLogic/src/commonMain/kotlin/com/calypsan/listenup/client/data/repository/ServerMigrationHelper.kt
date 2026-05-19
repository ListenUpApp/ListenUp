package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.local.db.ServerDao
import com.calypsan.listenup.client.data.local.db.ServerEntity

/**
 * Helper for migrating existing users from single-server to multi-server storage.
 *
 * Prior to v13, auth tokens were stored directly in SecureStorage with flat keys.
 * This helper migrates that data to a ServerEntity, preserving the user's session.
 *
 * Migration is idempotent - if servers already exist, no migration is performed.
 */
class ServerMigrationHelper(
    private val secureStorage: SecureStorage,
    private val serverDao: ServerDao,
) {
    companion object {
        // Legacy keys from SettingsRepository (pre-v13)
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_USER_ID = "user_id"
    }

    /**
     * Migrate legacy single-server data to multi-server model if needed.
     *
     * @return true if migration was performed, false if skipped (already migrated or no data)
     */
    suspend fun migrateIfNeeded(): Boolean {
        // Skip if servers already exist (already migrated or fresh install)
        val existingServers = serverDao.getAll()
        if (existingServers.isNotEmpty()) {
            return false
        }

        // Check for legacy data
        val serverUrl = secureStorage.read(KEY_SERVER_URL) ?: return false

        // Create server entity from legacy data
        val accessToken = secureStorage.read(KEY_ACCESS_TOKEN)
        val refreshToken = secureStorage.read(KEY_REFRESH_TOKEN)
        val sessionId = secureStorage.read(KEY_SESSION_ID)
        val userId = secureStorage.read(KEY_USER_ID)

        // Generate a stable ID from the URL (for matching if server is rediscovered)
        val legacyServerId = "legacy_${serverUrl.hashCode().toUInt()}"

        // Determine if URL is local or remote
        val isLocalUrl = isLocalUrl(serverUrl)

        val server =
            ServerEntity(
                id = legacyServerId,
                name = "My Server", // Will be updated when discovered via mDNS
                apiVersion = "v1",
                serverVersion = "unknown",
                localUrl = if (isLocalUrl) serverUrl else null,
                remoteUrl = if (!isLocalUrl) serverUrl else null,
                accessToken = accessToken,
                refreshToken = refreshToken,
                sessionId = sessionId,
                userId = userId,
                isActive = true, // This was the only server, so it's active
                lastSeenAt = 0, // Not discovered via mDNS
                lastConnectedAt = currentEpochMilliseconds(),
            )

        serverDao.upsert(server)

        // Clear legacy storage (tokens are now in database)
        clearLegacyStorage()

        return true
    }

    /**
     * Determine if a URL is a local network URL.
     */
    private fun isLocalUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("localhost") ||
            lower.contains("127.0.0.1") ||
            lower.contains("10.0.2.2") || // Android emulator
            lower.matches(Regex(""".*192\.168\.\d+\.\d+.*""")) ||
            lower.matches(Regex(""".*10\.\d+\.\d+\.\d+.*""")) ||
            lower.matches(Regex(""".*172\.(1[6-9]|2\d|3[01])\.\d+\.\d+.*"""))
    }

    /**
     * Clear legacy storage after migration.
     * Preserves sort preferences and other non-auth settings.
     */
    private suspend fun clearLegacyStorage() {
        secureStorage.delete(KEY_SERVER_URL)
        secureStorage.delete(KEY_ACCESS_TOKEN)
        secureStorage.delete(KEY_REFRESH_TOKEN)
        secureStorage.delete(KEY_SESSION_ID)
        secureStorage.delete(KEY_USER_ID)
    }
}
