package com.calypsan.listenup.server.auth

/** The JWT signing secret and refresh-token pepper, resolved once at startup. */
data class ServerSecrets(
    val jwtSecret: String,
    val refreshPepper: String,
)

/**
 * Source of a persistent server secret. [getOrGenerate] returns the stored value
 * for [key], generating and persisting a fresh one on first request.
 */
interface SecretStore {
    fun getOrGenerate(key: String): String
}

/** Committed test default for the JWT signing secret — the "unconfigured" sentinel. */
internal const val INSECURE_DEFAULT_JWT_SECRET =
    "test-jwt-secret-change-in-production-at-least-32-bytes-please"

/** Committed test default for the refresh-token pepper — the "unconfigured" sentinel. */
internal const val INSECURE_DEFAULT_REFRESH_PEPPER =
    "test-pepper-change-in-production-this-must-be-32-bytes-or-more"

private const val MIN_SECRET_BYTES = 32

/**
 * Resolves one server secret. An explicit, non-blank [rawEnv] wins but must be
 * secure (not the [committedDefault], at least [MIN_SECRET_BYTES] bytes) or this
 * throws. Otherwise a [configValue] equal to [committedDefault] means
 * "unconfigured" and is generated/persisted via [store]; any other [configValue]
 * (e.g. a test-injected one) is passed through.
 */
internal fun resolveSecret(
    rawEnv: String?,
    configValue: String,
    committedDefault: String,
    store: SecretStore,
    storeKey: String,
    envVarName: String,
): String {
    val explicit = rawEnv?.takeIf { it.isNotBlank() }
    if (explicit != null) {
        require(explicit != committedDefault && explicit.toByteArray(Charsets.UTF_8).size >= MIN_SECRET_BYTES) {
            "$envVarName is set to an insecure value — use a 32+ byte random value, " +
                "or unset it to have the server generate and persist one automatically."
        }
        return explicit
    }
    return if (configValue == committedDefault) store.getOrGenerate(storeKey) else configValue
}
