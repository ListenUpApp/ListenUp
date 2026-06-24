package com.calypsan.listenup.server.auth

import com.calypsan.listenup.server.io.createTempFileIn
import com.calypsan.listenup.server.io.restrictFileToOwner
import dev.whyoleg.cryptography.random.CryptographyRandom
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.io.encoding.Base64

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
        require(explicit != committedDefault && explicit.encodeToByteArray().size >= MIN_SECRET_BYTES) {
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
 * Persists server secrets as a `key=value` properties file at `$home/secrets.properties`,
 * locked to the owner. Each [getOrGenerate] reuses an existing key verbatim — the
 * generate-once invariant that keeps the refresh pepper stable across restarts.
 */
class FileSecretStore(
    private val home: Path,
) : SecretStore {
    private val file: Path = Path(home, SECRETS_FILENAME)

    override fun getOrGenerate(key: String): String {
        load()[key]?.takeIf { it.isNotBlank() }?.let { return it }

        val generated = generate()
        persist(key, generated)
        logger.info { "Generated and persisted a new server secret to $file" }
        return generated
    }

    private fun load(): Map<String, String> {
        if (!SystemFileSystem.exists(file)) return emptyMap()
        val text = SystemFileSystem.source(file).buffered().use { it.readString() }
        return parseSecretsProperties(text)
    }

    private fun persist(
        key: String,
        value: String,
    ) {
        SystemFileSystem.createDirectories(home)
        val merged = load() + (key to value)
        val tmp = createTempFileIn(home, SECRETS_FILENAME, ".tmp")
        SystemFileSystem.sink(tmp).buffered().use { it.writeString(renderSecretsProperties(merged)) }
        restrictFileToOwner(tmp)
        SystemFileSystem.atomicMove(tmp, file)
        restrictFileToOwner(file)
    }

    private fun generate(): String = URL_NO_PAD.encode(CryptographyRandom.nextBytes(SECRET_BYTE_LENGTH))

    companion object {
        private val URL_NO_PAD = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    }
}

/**
 * Parses the `key=value` subset of the `.properties` format we write: one entry per line, `=` as the
 * separator, with blank lines and `#`/`!` comment lines skipped. Sufficient — and bidirectionally
 * compatible with `java.util.Properties` — because the only values we store are base64url secrets
 * (ASCII, no `=`, no whitespace, no escapes), and a `java.util.Properties`-written file's auto
 * timestamp line is just a `#` comment we skip.
 */
private fun parseSecretsProperties(text: String): Map<String, String> =
    text
        .lineSequence()
        .map { it.trimStart() }
        .filter { it.isNotEmpty() && it[0] != '#' && it[0] != '!' }
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) {
                null
            } else {
                val key = line.substring(0, separator).trim()
                key to line.substring(separator + 1).trimStart()
            }
        }.toMap()

/** Renders [entries] as a `java.util.Properties`-compatible `key=value` file with a header comment. */
private fun renderSecretsProperties(entries: Map<String, String>): String =
    buildString {
        append("#ListenUp server secrets - auto-generated, keep private\n")
        for ((key, value) in entries) append(key).append('=').append(value).append('\n')
    }
