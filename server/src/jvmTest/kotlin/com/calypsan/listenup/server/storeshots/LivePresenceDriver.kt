package com.calypsan.listenup.server.storeshots

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.bearerAuth
import kotlinx.coroutines.delay
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Keeps a screenshot rig's other listeners "listening now" while the store screenshots are captured.
 *
 * Not a test: a driver the store-screenshot scripts (`tools/scripts/store-screenshots-*.sh`) start in
 * the background and stop when the capture ends. Book Detail and Discover show a listener as live when
 * their position was written in the last five minutes, so this re-posts each named listener's position
 * on their book every two minutes, through the server's real RPC surface, advancing it as if they were
 * still playing.
 *
 * It runs only when [CONFIG_ENV] names a config file, so the `:server:jvmTest` lane skips it:
 *
 * ```json
 * { "baseUrl": "http://kit:8099", "stopFile": "/tmp/…/stop", "logFile": "/tmp/…/live.log", "maxMinutes": 90,
 *   "listeners": [{ "email": "…", "password": "…", "bookId": "…" }] }
 * ```
 *
 * The scripts write that file from the rig's credentials with owner-only permissions and delete it
 * afterwards. Passwords are never logged.
 */
class LivePresenceDriver :
    FunSpec({
        val configPath = System.getenv(CONFIG_ENV)
        test("keeps the rig's listeners live").config(enabled = configPath != null, timeout = MAX_RUN) {
            val config = Json.decodeFromString<LiveConfig>(File(checkNotNull(configPath)).readText())
            LivePresence(config).run()
        }
    })

private const val CONFIG_ENV = "LISTENUP_LIVE_PRESENCE_CONFIG"
private val MAX_RUN = 180.minutes
private val REPOST_EVERY = 2.minutes
private val STOP_POLL = 5.seconds

@Serializable
private data class LiveConfig(
    val baseUrl: String,
    val stopFile: String,
    val logFile: String,
    val maxMinutes: Long,
    val listeners: List<LiveListener>,
)

@Serializable
private data class LiveListener(
    val email: String,
    val password: String,
    val bookId: String,
) {
    override fun toString(): String = "LiveListener(email=$email, bookId=$bookId)"
}

private class LivePresence(
    private val config: LiveConfig,
) {
    private val wsUrl = config.baseUrl.replaceFirst("http", "ws")
    private val http =
        HttpClient(CIO) {
            install(WebSockets)
            installKrpc()
        }
    private val publicAuth by lazy {
        http
            .rpc("$wsUrl/api/rpc/public") { rpcConfig { serialization { json(contractJson) } } }
            .withService<AuthServicePublic>()
    }

    suspend fun run() {
        val started = Clock.System.now()
        val until = started + config.maxMinutes.minutes
        while (Clock.System.now() < until && !File(config.stopFile).exists()) {
            config.listeners.forEach { repost(it) }
            waitForNextRound()
        }
        log("stopped")
    }

    private suspend fun waitForNextRound() {
        val next = Clock.System.now() + REPOST_EVERY
        while (Clock.System.now() < next && !File(config.stopFile).exists()) delay(STOP_POLL)
    }

    /** Moves [listener]'s position on by the time since their last write, stamped now. */
    private suspend fun repost(listener: LiveListener) {
        val playback = playbackFor(listener) ?: return
        val existing = (playback.getPosition(BookId(listener.bookId)) as? AppResult.Success)?.data
        if (existing == null) {
            log("${listener.email}: no position on ${listener.bookId}")
            return
        }
        val now = Clock.System.now()
        val listened = (now - Instant.fromEpochMilliseconds(existing.lastPlayedAt)).coerceAtMost(REPOST_EVERY)
        val result =
            playback.recordPosition(
                RecordPositionRequest(
                    bookId = listener.bookId,
                    positionMs = existing.positionMs + (listened.inWholeMilliseconds * existing.playbackSpeed).toLong(),
                    lastPlayedAt = now.toEpochMilliseconds(),
                    finished = false,
                    playbackSpeed = existing.playbackSpeed,
                    currentChapterId = existing.currentChapterId,
                    hasCustomSpeed = existing.hasCustomSpeed,
                ),
            )
        log("${listener.email}: ${if (result is AppResult.Success) "live" else "write failed"}")
    }

    private fun log(line: String) {
        File(config.logFile).appendText("[live] $line\n")
    }

    private suspend fun playbackFor(listener: LiveListener): PlaybackService? =
        when (
            val login =
                publicAuth.login(
                    LoginRequest(listener.email, listener.password, sessionLabel = "Screenshot rig"),
                )
        ) {
            is AppResult.Success -> {
                http
                    .rpc("$wsUrl/api/rpc/authed") {
                        rpcConfig { serialization { json(contractJson) } }
                        bearerAuth(login.data.accessToken.value)
                    }.withService<PlaybackService>()
            }

            is AppResult.Failure -> {
                log("${listener.email}: sign-in failed (${login.error.code})")
                null
            }
        }
}
