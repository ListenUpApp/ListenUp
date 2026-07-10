package com.calypsan.listenup.server.settings

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.api.ServerIdentity
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/**
 * Read/write access to the server-wide key/value `server_settings` table.
 *
 * Today this is a single concern — the instance-wide [RegistrationPolicy] — but
 * the table is deliberately a generic key/value store so future server-level
 * toggles land here without another migration. [default] is the fallback when
 * the policy row is missing or holds an unrecognised value (forward-compat: an
 * older server reading a value a newer server wrote).
 */
class ServerSettingsRepository(
    private val sql: ListenUpDatabase,
    private val default: RegistrationPolicy,
) {
    /** The current instance-wide registration policy, or [default] when unset/unknown. */
    suspend fun registrationPolicy(): RegistrationPolicy =
        suspendTransaction(sql) {
            sql.serverSettingsQueries
                .selectValueByKey(KEY_REGISTRATION_POLICY)
                .executeAsOneOrNull()
                ?.let { raw -> runCatching { RegistrationPolicy.valueOf(raw) }.getOrNull() }
                ?: default
        }

    /** Replaces the instance-wide registration policy with [policy]. */
    suspend fun setRegistrationPolicy(policy: RegistrationPolicy) {
        suspendTransaction(sql) {
            sql.serverSettingsQueries.upsert(KEY_REGISTRATION_POLICY, policy.name)
        }
    }

    /** Raw value for [key], or null if unset. Generic KV access for non-policy settings. */
    suspend fun getValue(key: String): String? =
        suspendTransaction(sql) {
            sql.serverSettingsQueries
                .selectValueByKey(key)
                .executeAsOneOrNull()
        }

    /** Upserts [key] → [value]. */
    suspend fun setValue(
        key: String,
        value: String,
    ) {
        suspendTransaction(sql) {
            sql.serverSettingsQueries.upsert(key, value)
        }
    }

    /** The operator-set server name, or [ServerIdentity.NAME] when unset. */
    suspend fun serverName(): String = getValue(KEY_SERVER_NAME) ?: ServerIdentity.NAME

    /** Replaces the server name. */
    suspend fun setServerName(name: String) = setValue(KEY_SERVER_NAME, name)

    /** The operator-set public remote URL, or null when unset/blank. */
    suspend fun remoteUrl(): String? = getValue(KEY_REMOTE_URL)?.takeIf { it.isNotBlank() }

    /** Replaces the remote URL; an empty/blank [url] clears it. */
    suspend fun setRemoteUrl(url: String) = setValue(KEY_REMOTE_URL, url.trim())

    /** Whether push notifications are enabled for this instance, defaulting to `true` when unset/unrecognised. */
    suspend fun pushNotificationsEnabled(): Boolean =
        getValue(KEY_PUSH_NOTIFICATIONS_ENABLED)?.toBooleanStrictOrNull() ?: true

    /** Replaces the push-notifications admin toggle. */
    suspend fun setPushNotificationsEnabled(enabled: Boolean) =
        setValue(KEY_PUSH_NOTIFICATIONS_ENABLED, enabled.toString())

    private companion object {
        const val KEY_REGISTRATION_POLICY = "registration_policy"
        const val KEY_SERVER_NAME = "server_name"
        const val KEY_REMOTE_URL = "remote_url"
        const val KEY_PUSH_NOTIFICATIONS_ENABLED = "push_notifications_enabled"
    }
}
