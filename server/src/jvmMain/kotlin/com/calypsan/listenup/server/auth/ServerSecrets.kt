package com.calypsan.listenup.server.auth

import com.calypsan.listenup.server.db.resolveListenupHome
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.config.ApplicationConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties

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

private val logger = KotlinLogging.logger {}

private const val SECRETS_FILENAME = "secrets.properties"
private const val SECRET_BYTE_LENGTH = 48

/**
 * Persists server secrets as a Java `Properties` file at `$home/secrets.properties`,
 * locked to the owner. Each [getOrGenerate] reuses an existing key verbatim — the
 * generate-once invariant that keeps the refresh pepper stable across restarts.
 */
class FileSecretStore(
    private val home: Path,
) : SecretStore {
    private val file: Path = home.resolve(SECRETS_FILENAME)

    override fun getOrGenerate(key: String): String {
        load().getProperty(key)?.takeIf { it.isNotBlank() }?.let { return it }

        val generated = generate()
        persist(key, generated)
        logger.info { "Generated and persisted a new server secret to $file" }
        return generated
    }

    private fun load(): Properties {
        val props = Properties()
        if (Files.exists(file)) {
            Files.newInputStream(file).use { props.load(it) }
        }
        return props
    }

    private fun persist(
        key: String,
        value: String,
    ) {
        Files.createDirectories(home)
        val props = load().apply { setProperty(key, value) }
        val tmp = Files.createTempFile(home, SECRETS_FILENAME, ".tmp")
        Files.newOutputStream(tmp).use { props.store(it, "ListenUp server secrets — auto-generated, keep private") }
        restrictToOwner(tmp)
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        restrictToOwner(file)
    }

    /** Best-effort `600` permissions; a no-op on non-POSIX filesystems. */
    private fun restrictToOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }.onFailure { logger.debug(it) { "Could not restrict permissions on $path" } }
    }

    private fun generate(): String {
        val bytes = ByteArray(SECRET_BYTE_LENGTH)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * Resolves both server secrets from [config] at startup. Reads the raw
 * `LISTENUP_JWT_SECRET` / `LISTENUP_REFRESH_PEPPER` env vars (the explicit channel)
 * and `LISTENUP_HOME` directly, then delegates to [resolveSecret]. Unconfigured
 * secrets are generated/persisted under `$LISTENUP_HOME/secrets.properties`.
 */
fun resolveServerSecrets(config: ApplicationConfig): ServerSecrets {
    // Honour `listenup.home` (config) so secrets.properties lands under the SAME home as the DB and
    // covers/spool — all server data stays under one directory (#703).
    val home =
        resolveListenupHome(
            configuredHome = config.propertyOrNull("listenup.home")?.getString(),
            envHome = System.getenv("LISTENUP_HOME"),
            userHome = System.getProperty("user.home"),
        )
    val store = FileSecretStore(home)
    return ServerSecrets(
        jwtSecret =
            resolveSecret(
                rawEnv = System.getenv("LISTENUP_JWT_SECRET"),
                configValue = config.property("jwt.secret").getString(),
                committedDefault = INSECURE_DEFAULT_JWT_SECRET,
                store = store,
                storeKey = "jwt.secret",
                envVarName = "LISTENUP_JWT_SECRET",
            ),
        refreshPepper =
            resolveSecret(
                rawEnv = System.getenv("LISTENUP_REFRESH_PEPPER"),
                configValue = config.property("auth.refreshPepper").getString(),
                committedDefault = INSECURE_DEFAULT_REFRESH_PEPPER,
                store = store,
                storeKey = "auth.refreshPepper",
                envVarName = "LISTENUP_REFRESH_PEPPER",
            ),
    )
}
