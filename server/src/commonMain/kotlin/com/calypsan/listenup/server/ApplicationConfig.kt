@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server

import com.calypsan.listenup.server.auth.RootResetToken
import com.calypsan.listenup.server.db.DataDirLock
import com.calypsan.listenup.server.db.resolveListenupHome
import com.calypsan.listenup.server.io.readEnv
import com.calypsan.listenup.server.io.userHomeDir
import com.calypsan.listenup.server.push.PushConfig
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.config.ApplicationConfig
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private const val DEFAULT_EMBEDDED_COVER_CACHE_SIZE = 1000

/**
 * Reads `scanner.libraryPath` from configuration. Accepts a OS-path-separator-delimited
 * list of folder paths (e.g. `/audio/books:/audio/podcasts` on Unix). Each entry is
 * trimmed and validated; non-directory entries are skipped with a warning. Returns an
 * empty list when the config key is unset or blank — the server still starts in that
 * case, just without any seeded folders.
 */
internal fun Application.resolveLibraryPaths(): List<Path> {
    val raw =
        environment.config
            .propertyOrNull("scanner.libraryPath")
            ?.getString()
            .orEmpty()
    if (raw.isBlank()) return emptyList()
    return raw
        .split(':')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { entry ->
            val path = Path(entry)
            if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
                path
            } else {
                logger.warn { "scanner.libraryPath entry '$entry' is not a directory — skipping" }
                null
            }
        }
}

/**
 * Resolves the always-available ListenUp home directory that holds app-managed
 * files (downloaded cover/contributor images live in per-type subdirectories
 * under it). Unlike the audio library, this path is always available — even on a
 * library-less boot — so it is resolved unconditionally.
 *
 * An explicit `listenup.home` config property wins (tests inject this to a temp
 * dir); otherwise it falls back to `$LISTENUP_HOME`, defaulting to `~/ListenUp`.
 * Reads env / system properties here at the config edge so [resolveListenupHome]
 * stays pure.
 */
internal fun Application.resolveImageHome(): Path =
    resolveListenupHome(
        configuredHome = environment.config.propertyOrNull("listenup.home")?.getString(),
        envHome = readEnv("LISTENUP_HOME"),
        userHome = userHomeDir(),
    )

/**
 * Takes an exclusive lock on the data directory when `server.dataDirLock` is enabled (production
 * default in `application.conf`), so a second server on the same `$LISTENUP_HOME` fails fast with a
 * clear message instead of racing the scan-spool (a stale JVM whose covers get swept by a
 * fresh boot). Off by default in code, so the isolated test configs (which omit the key) never
 * lock. The lock is released on [ApplicationStopped]; the OS frees it anyway on process death.
 */
internal fun Application.acquireDataDirLockIfEnabled(homeDir: Path) {
    val enabled =
        environment.config
            .propertyOrNull("server.dataDirLock")
            ?.getString()
            ?.toBooleanStrictOrNull() ?: false
    if (!enabled) return
    val lock = DataDirLock.forDataHome(homeDir)
    check(lock.tryAcquire()) {
        "Another ListenUp server is already using the data directory $homeDir. Stop it before " +
            "starting another instance, or point this one at a different LISTENUP_HOME."
    }
    monitor.subscribe(ApplicationStopped) { lock.close() }
}

/**
 * Reads `scanner.metadataPrecedence` from configuration and parses it into a
 * [MetadataPrecedence]. A blank value yields [MetadataPrecedence.DEFAULT].
 *
 * An invalid token throws [IllegalArgumentException] — deliberately left to
 * propagate so a misconfigured precedence fails server startup loud rather
 * than silently scanning with the default order.
 */
internal fun Application.resolveMetadataPrecedence(): MetadataPrecedence {
    val raw =
        environment.config
            .propertyOrNull("scanner.metadataPrecedence")
            ?.getString()
            .orEmpty()
    return MetadataPrecedence.parse(raw)
}

/**
 * Reads `scanner.embeddedCoverCacheSize` from configuration. Falls back to
 * [DEFAULT_EMBEDDED_COVER_CACHE_SIZE] when unset or blank.
 *
 * A non-numeric value throws [NumberFormatException] — deliberately left to
 * propagate so a misconfigured cache size fails server startup loud rather than
 * silently running with the default.
 */
internal fun Application.resolveEmbeddedCoverCacheSize(): Int {
    val raw =
        environment.config
            .propertyOrNull("scanner.embeddedCoverCacheSize")
            ?.getString()
            ?.trim()
            .orEmpty()
    if (raw.isBlank()) return DEFAULT_EMBEDDED_COVER_CACHE_SIZE
    return raw.toInt()
}

/**
 * Reads `seed.profile` from configuration. Returns the trimmed value, or null when
 * unset/blank. Only `"demo"` is recognized today; an unrecognized non-blank value is
 * returned as null and logged as ignored.
 */
internal fun Application.resolveSeedProfile(): String? {
    val raw =
        environment.config
            .propertyOrNull("seed.profile")
            ?.getString()
            ?.trim()
            .orEmpty()
    if (raw.isBlank()) return null
    if (raw != SEED_PROFILE_DEMO) {
        logger.warn { "seed.profile '$raw' is not a recognized profile — ignoring" }
        return null
    }
    return raw
}

/**
 * When the demo seed profile is active and no explicit `scanner.libraryPath` is set, fall back to
 * the generated synthetic library at `build/seed-library` (produced by `:server:generateSeedLibrary`).
 * Returns null — with a guiding log line — if that directory does not exist yet.
 */
internal fun Application.resolveDemoLibraryFallback(seedProfile: String?): Path? {
    if (seedProfile != SEED_PROFILE_DEMO) return null
    val candidate = Path("build", "seed-library")
    if (SystemFileSystem.metadataOrNull(candidate)?.isDirectory != true) {
        logger.warn {
            "seed.profile=demo but no synthetic library at '$candidate' — run " +
                "':server:generateSeedLibrary' (or use ':server:runDemo'). The demo user is still " +
                "seeded; the library will be empty until then."
        }
        return null
    }
    logger.info { "seed.profile=demo — scanning the generated synthetic library at '$candidate'" }
    return candidate
}

/**
 * Push relay URL: `push.relayUrl` config key, else LISTENUP_PUSH_RELAY_URL env,
 * else the ListenUp project relay. The admin setting pushNotificationsEnabled
 * (default ON) is the on/off switch; this only picks WHICH relay.
 */
internal fun Application.resolvePushRelayUrl(): String {
    val fromConfig = environment.config.propertyOrNull("push.relayUrl")?.getString()
    val fromEnv = readEnv("LISTENUP_PUSH_RELAY_URL")
    return (fromConfig ?: fromEnv)?.trim()?.takeIf { it.isNotEmpty() } ?: PushConfig.DEFAULT_RELAY_URL
}

/**
 * Push relay sender credential: `push.senderToken` config key, else LISTENUP_PUSH_SENDER_TOKEN env,
 * else null.
 *
 * Null is a valid, working configuration today — the relay's rollout accepts an absent credential
 * during its migration window (PROTOCOL.md "Sender credential") — but stops being one when that
 * window closes. A fork pointing at its own relay leaves this unset.
 */
internal fun Application.resolvePushSenderToken(): String? {
    val fromConfig = environment.config.propertyOrNull("push.senderToken")?.getString()
    val fromEnv = readEnv("LISTENUP_PUSH_SENDER_TOKEN")
    return (fromConfig ?: fromEnv)?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun ApplicationConfig.rescanOnStartup(): Boolean =
    propertyOrNull("scan.rescanOnStartup")?.getString()?.toBoolean() ?: true

/**
 * Whether to probe for FFmpeg at boot. Defaults to true.
 *
 * Tests turn it off so the transcoding surface is deterministic: with the probe running, whether a
 * route answers depends on whether the *host* happens to have FFmpeg installed, and a test that
 * publishes its own availability races the probe overwriting it.
 */
internal fun ApplicationConfig.transcodeProbeOnStartup(): Boolean =
    propertyOrNull("transcode.probeOnStartup")?.getString()?.toBoolean() ?: true

/**
 * Reads `scanner.watchEnabled` — gates whether [ScanOrchestrator.onLibraryAdded]
 * mounts real-time file-system watchers. Defaults to `true` (production keeps the
 * live `WatchService`). Tests set it `false` so a fixture write into the library
 * root can't trigger a scan that races the seed (mirrors the `mdns.enabled` gate).
 */
internal fun ApplicationConfig.watchEnabled(): Boolean =
    propertyOrNull("scanner.watchEnabled")?.getString()?.toBoolean() ?: true

internal fun ApplicationConfig.periodicRescanInterval(): Duration =
    propertyOrNull("scan.periodicRescanInterval")
        ?.getString()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Duration.parse(it) }.getOrNull() }
        ?: Duration.ZERO

/**
 * Arms the root-password escape hatch when `LISTENUP_ROOT_RESET` is set to a truthy value, and
 * prints its one-time token. The warning is deliberately blunt: an operator who leaves this set
 * is re-arming the hatch on every restart, and needs to be told so.
 *
 * Reads the env var directly (the [readEnv] idiom [resolveImageHome] already uses for
 * `LISTENUP_HOME`) rather than through Ktor's `Application.environment.config` — this is a
 * plain function, not an `Application` extension, so it can be called from inside a Koin module
 * builder (`passwordResetModule`), which only ever sees the Ktor `ApplicationConfig`, not the
 * live `Application`.
 *
 * ⚠️ This deliberately logs a secret, which the project's "never log sensitive data" rule
 * otherwise forbids. It is the one exception: the operator has no other channel to learn the
 * token, and without it the hatch cannot be used at all — a hatch nobody can open is not a
 * hatch.
 */
internal fun resolveRootResetToken(clock: Clock): RootResetToken {
    val enabled = readEnv("LISTENUP_ROOT_RESET")?.toBooleanStrictOrNull() ?: false
    if (!enabled) return RootResetToken.disarmed()

    val armed = RootResetToken.armed(clock)
    val banner = "=".repeat(ROOT_RESET_BANNER_WIDTH)
    logger.warn {
        buildString {
            appendLine(banner)
            appendLine("ROOT PASSWORD RESET IS ARMED for the next ${RootResetToken.WINDOW}.")
            appendLine("One-time token: ${armed.token}")
            appendLine("Anyone who can reach the login screen AND read this log can take the")
            appendLine("root account. Reset the password now, then REMOVE LISTENUP_ROOT_RESET")
            appendLine("and restart — leaving it set re-arms the hatch on every boot.")
            append(banner)
        }
    }
    return armed
}

/** Width of the `=`-rule framing the root-reset startup warning — purely cosmetic. */
private const val ROOT_RESET_BANNER_WIDTH = 72

/**
 * Reads `web.root` from configuration — the directory holding the bundled web client
 * (`app/webApp/web`'s `vite build` output). Returns null when unset, blank, or not a directory,
 * in which case no web-app route is mounted at all.
 *
 * Absent rather than empty is the honest default: a server image built without the web bundle
 * should 404 at `/`, not serve a shell that loads nothing.
 */
internal fun Application.resolveWebRoot(): Path? {
    val raw =
        environment.config
            .propertyOrNull("web.root")
            ?.getString()
            ?.trim()
            .orEmpty()
    if (raw.isBlank()) return null
    val candidate = Path(raw)
    if (SystemFileSystem.metadataOrNull(candidate)?.isDirectory != true) {
        return null
    }
    return candidate
}
