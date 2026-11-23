package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.CodecCapability
import com.calypsan.listenup.api.dto.PreparePurpose
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.dto.RecordPositionResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.rpc.krpc.KrpcConfig
import kotlinx.rpc.krpc.KrpcTransport
import kotlinx.rpc.krpc.KrpcTransportMessage
import kotlinx.rpc.krpc.client.KrpcClient
import kotlinx.rpc.krpc.rpcClientConfig
import kotlinx.rpc.krpc.rpcServerConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.krpc.server.KrpcServer
import kotlinx.rpc.registerService
import kotlinx.rpc.withService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext

/**
 * `prepare()` gained [PreparePurpose], and clients already in people's hands do not send it.
 *
 * An older build's call carries one key fewer, so this spec makes exactly that call: a real kRPC
 * client and server joined in process, with the client's outgoing frames rewritten to drop the
 * `purpose` key before the server reads them — the bytes a build that predates the parameter puts
 * on the wire. That call must arrive (with no purpose, which `PlaybackServiceImpl` reads as a
 * stream), and a newer client's key a server does not know must be ignored, which is how a new
 * client keeps working against an old server.
 */
class PreparePurposeWireCompatibilityTest :
    FunSpec({
        test("a newer client's download purpose reaches the server") {
            withWire(rewrite = { it }) { service, received ->
                service
                    .prepare(BookId("b1"), purpose = PreparePurpose.Download)
                    .shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                received shouldBe listOf(PreparePurpose.Download)
            }
        }

        test("a call from a client that predates the purpose arrives with none, not a failure") {
            var stripped = 0
            withWire(rewrite = { frame -> frame.withoutKey("purpose").also { if (it != frame) stripped++ } }) {
                service,
                received,
                ->
                service
                    .prepare(BookId("b1"), purpose = PreparePurpose.Download)
                    .shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                // The rewrite really removed the key; without this the spec could pass on an untouched call.
                stripped shouldBe 1
                // kRPC does not apply the interface default server-side: the server sees null, which
                // is why the parameter is nullable and the server reads null as a stream.
                received shouldBe listOf(null)
            }
        }

        test("a parameter the server does not know is ignored, so a newer client works against an older server") {
            withWire(rewrite = { it.withExtraArgument("aParameterFromTheFuture", "x") }) { service, received ->
                service
                    .prepare(BookId("b1"))
                    .shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                received shouldBe listOf(PreparePurpose.Stream)
            }
        }
    })

/**
 * Runs [block] against a [PlaybackService] proxy whose calls cross a real kRPC client and server.
 * Every client-to-server frame passes through [rewrite] first.
 */
private suspend fun withWire(
    rewrite: (String) -> String,
    block: suspend (PlaybackService, List<PreparePurpose?>) -> Unit,
) {
    val received = mutableListOf<PreparePurpose?>()
    val clientToWire = Channel<KrpcTransportMessage>(Channel.UNLIMITED)
    val wireToServer = Channel<KrpcTransportMessage>(Channel.UNLIMITED)
    val serverToClient = Channel<KrpcTransportMessage>(Channel.UNLIMITED)
    val scope = CoroutineScope(Job())
    val relay =
        scope.launch {
            for (message in clientToWire) {
                val rewritten =
                    if (message is KrpcTransportMessage.StringMessage) {
                        KrpcTransportMessage.StringMessage(rewrite(message.value))
                    } else {
                        message
                    }
                wireToServer.send(rewritten)
            }
        }
    val server =
        object : KrpcServer(
            rpcServerConfig { serialization { json(contractJson) } },
            ChannelTransport(serverToClient, wireToServer, scope.coroutineContext),
        ) {}
    server.registerService<PlaybackService> { RecordingPrepare(received) }
    val client =
        object : KrpcClient() {
            override suspend fun initializeTransport(): KrpcTransport =
                ChannelTransport(clientToWire, serverToClient, scope.coroutineContext)

            override fun initializeConfig(): KrpcConfig.Client = rpcClientConfig { serialization { json(contractJson) } }
        }
    try {
        block(client.withService<PlaybackService>(), received)
    } finally {
        client.close()
        server.close()
        relay.cancelAndJoin()
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}

private class ChannelTransport(
    private val outgoing: Channel<KrpcTransportMessage>,
    private val incoming: Channel<KrpcTransportMessage>,
    override val coroutineContext: CoroutineContext,
) : KrpcTransport {
    override suspend fun send(message: KrpcTransportMessage) = outgoing.send(message)

    override suspend fun receive(): KrpcTransportMessage = incoming.receive()
}

/**
 * The frame with [key] removed from any JSON object inside it, including the call's arguments, which
 * kRPC carries as a JSON string nested in the frame.
 */
private fun String.withoutKey(key: String): String = mapNestedObjects(this) { JsonObject(it - key) }

/** The frame with an extra argument added to the call it carries. */
private fun String.withExtraArgument(
    key: String,
    value: String,
): String =
    mapNestedObjects(this) { args ->
        if ("bookId" in args) JsonObject(args + (key to JsonPrimitive(value))) else JsonObject(args)
    }

private fun mapNestedObjects(
    frame: String,
    transform: (JsonObject) -> JsonObject,
): String {
    val parsed = runCatching { contractJson.parseToJsonElement(frame) }.getOrNull() ?: return frame
    return contractJson.encodeToString(JsonElement.serializer(), parsed.mapObjects(transform))
}

private fun JsonElement.mapObjects(transform: (JsonObject) -> JsonObject): JsonElement =
    if (this is JsonObject) {
        transform(
            JsonObject(
                mapValues { (_, child) ->
                    val nested =
                        (child as? JsonPrimitive)
                            ?.takeIf { it.isString && it.content.startsWith("{") }
                            ?.let { runCatching { contractJson.parseToJsonElement(it.jsonPrimitive.content) }.getOrNull() }
                    if (nested != null) {
                        JsonPrimitive(contractJson.encodeToString(JsonElement.serializer(), nested.mapObjects(transform)))
                    } else {
                        child.mapObjects(transform)
                    }
                },
            ),
        )
    } else {
        this
    }

/** Answers `prepare()` with an empty book and records the purpose it was asked for; nothing else is called. */
private class RecordingPrepare(
    private val received: MutableList<PreparePurpose?>,
) : PlaybackService {
    override suspend fun prepare(
        bookId: BookId,
        capabilities: Set<CodecCapability>?,
        forceTranscode: Boolean,
        purpose: PreparePurpose?,
    ): AppResult<PreparedPlayback> {
        received += purpose
        return AppResult.Success(
            PreparedPlayback(bookId = bookId.value, audioFiles = emptyList(), resumePosition = null),
        )
    }

    override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> = unused()

    override suspend fun recordPosition(request: RecordPositionRequest): AppResult<RecordPositionResult> = unused()

    override suspend fun recordListeningEvent(request: RecordListeningEventRequest): AppResult<ListeningEventSyncPayload> = unused()

    private fun unused(): Nothing = error("not part of this test")
}
