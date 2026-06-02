package com.calypsan.listenup.server.settings

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

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
    private val db: Database,
    private val default: RegistrationPolicy,
) {
    /** The current instance-wide registration policy, or [default] when unset/unknown. */
    suspend fun registrationPolicy(): RegistrationPolicy =
        suspendTransaction(db) {
            ServerSettingsTable
                .selectAll()
                .where { ServerSettingsTable.key eq KEY_REGISTRATION_POLICY }
                .firstOrNull()
                ?.get(ServerSettingsTable.value)
                ?.let { raw -> runCatching { RegistrationPolicy.valueOf(raw) }.getOrNull() }
                ?: default
        }

    /** Replaces the instance-wide registration policy with [policy]. */
    suspend fun setRegistrationPolicy(policy: RegistrationPolicy) {
        suspendTransaction(db) {
            ServerSettingsTable.upsert {
                it[key] = KEY_REGISTRATION_POLICY
                it[value] = policy.name
            }
        }
    }

    /** Raw value for [key], or null if unset. Generic KV access for non-policy settings. */
    suspend fun getValue(key: String): String? =
        suspendTransaction(db) {
            ServerSettingsTable
                .selectAll()
                .where { ServerSettingsTable.key eq key }
                .firstOrNull()
                ?.get(ServerSettingsTable.value)
        }

    /** Upserts [key] → [value]. */
    suspend fun setValue(
        key: String,
        value: String,
    ) {
        suspendTransaction(db) {
            ServerSettingsTable.upsert {
                it[ServerSettingsTable.key] = key
                it[ServerSettingsTable.value] = value
            }
        }
    }

    private companion object {
        const val KEY_REGISTRATION_POLICY = "registration_policy"
    }
}

/** Persistence schema for the `server_settings` table created by V26. */
object ServerSettingsTable : Table("server_settings") {
    val key = text("key")
    val value = text("value")

    override val primaryKey = PrimaryKey(key)
}
