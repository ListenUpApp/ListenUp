package com.calypsan.listenup.server.playback

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.CodecCapability
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.dto.RecordPositionResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.foundation.FoundationDeps
import com.calypsan.listenup.server.foundation.foundationServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.routing.routing
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.registerService
import kotlinx.rpc.withService

private const val JWT_SECRET_LEN = 32

/**
 * Native (linuxX64) proof that a finish-card dismissal survives kotlinx.rpc on the server `server.kexe` runs, both
 * ways: in the request and in the stored row the answer carries. The service is a stub; the transport is on trial.
 */
class FinishedCardDismissalNativeRpcTest :
    FunSpec({
        test("a dismissal reaches recordPosition and comes back in its answer over native krpc") {
            val received = mutableListOf<RecordPositionRequest>()
            val server =
                foundationServer(
                    port = 0,
                    deps =
                        FoundationDeps(
                            JwtConfiguration("x".repeat(JWT_SECRET_LEN), "listenup", "listenup-client"),
                            sessionLiveness = { _, _ -> true },
                        ),
                ) {
                    routing {
                        rpc("/api/rpc/public") {
                            rpcConfig { serialization { json(contractJson) } }
                            registerService<PlaybackService> { RecordingPlayback(received) }
                        }
                    }
                }
            server.start(wait = false)
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val client =
                    HttpClient(ClientCIO) {
                        install(ClientWebSockets)
                        installKrpc()
                    }
                try {
                    val rpc =
                        client.rpc("ws://127.0.0.1:$port/api/rpc/public") {
                            rpcConfig { serialization { json(contractJson) } }
                        }
                    val answer =
                        rpc.withService<PlaybackService>().recordPosition(
                            RecordPositionRequest(
                                bookId = "b1",
                                positionMs = 9_000L,
                                lastPlayedAt = 5L,
                                finished = true,
                                playbackSpeed = 1.0f,
                                currentChapterId = null,
                                finishedCardDismissedAt = 9L,
                            ),
                        )

                    received.map { it.finishedCardDismissedAt } shouldBe listOf(9L)
                    (answer as AppResult.Success).data.position.finishedCardDismissedAt shouldBe 9L
                } finally {
                    client.close()
                }
            } finally {
                server.stop(0, 0)
            }
        }
    })

/** Records each position write and answers with a row that holds its dismissal; nothing else is called. */
private class RecordingPlayback(
    private val received: MutableList<RecordPositionRequest>,
) : PlaybackService {
    override suspend fun recordPosition(request: RecordPositionRequest): AppResult<RecordPositionResult> {
        received += request
        return AppResult.Success(
            RecordPositionResult(
                position =
                    PlaybackPositionSyncPayload(
                        id = "p1",
                        bookId = request.bookId,
                        positionMs = request.positionMs,
                        lastPlayedAt = request.lastPlayedAt,
                        finished = request.finished,
                        playbackSpeed = request.playbackSpeed,
                        currentChapterId = null,
                        finishedCardDismissedAt = request.finishedCardDismissedAt,
                        revision = 1L,
                        updatedAt = 1L,
                        createdAt = 1L,
                        deletedAt = null,
                    ),
                accepted = false,
                serverNowMs = 1L,
            ),
        )
    }

    override suspend fun prepare(
        bookId: BookId,
        capabilities: Set<CodecCapability>?,
        forceTranscode: Boolean,
    ): AppResult<PreparedPlayback> = unused()

    override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> = unused()

    override suspend fun getStats(): AppResult<UserStatsSyncPayload?> = unused()

    override suspend fun recordListeningEvent(request: RecordListeningEventRequest): AppResult<ListeningEventSyncPayload> = unused()

    private fun unused(): Nothing = error("not part of this test")
}
