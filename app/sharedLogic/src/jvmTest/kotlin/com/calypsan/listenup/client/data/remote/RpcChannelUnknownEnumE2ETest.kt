package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.MatchingService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.match.BookCandidate
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.YourCopy
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.di.e2e.TestServerConfig
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.rpc.krpc.ktor.server.Krpc as ServerKrpc
import kotlinx.rpc.krpc.ktor.server.rpc as serverRpc
import kotlinx.rpc.krpc.serialization.json.json as krpcJson

private const val REAL_MOUNT = "/real-rpc"
private const val FUTURE_TIER = "LEGENDARY"

/**
 * What a too-old client gets when a newer server sends a literal it has never heard of in a field
 * that is STRICT BY DECISION (see the Konsist rule `EnumWireFieldHasFallbackOrIsStrictRule`):
 * here `BookCandidate.tier`, in the Match-details round trip.
 *
 * Real sockets, the real kotlinx.rpc codec on both ends and the production [RpcProxyCache] engine
 * behind [RpcChannel]. Both ends are built from the same classes, so the "newer server" is a relay
 * in front of the real kRPC mount that rewrites one enum literal in the frames going to the client.
 *
 * The pinned answer: the call folds to [TransportError.DataMalformed] ("Server response was
 * malformed.", not retryable) — it does not throw, hang or poison the channel — and the next call
 * on the same channel works.
 *
 * `runBlocking`, not `runTest`: real I/O on the real clock.
 */
class RpcChannelUnknownEnumE2ETest :
    FunSpec({
        val candidate =
            BookCandidate(
                key = BookCandidateKey(listOf(ExternalRef("hardcover", "427578"))),
                title = "Project Hail Mary",
                subtitle = null,
                authors = listOf("Andy Weir"),
                narrators = emptyList(),
                durationMs = null,
                year = null,
                format = null,
                chapterCount = null,
                coverUrl = null,
                foundIn = emptyList(),
                tier = MatchTier.STRONG,
                score = 0.9,
                isBest = true,
                isCurrentLink = false,
                reasons = emptyList(),
            )
        val found =
            BookFindResult(
                yourCopy = YourCopy(durationMs = null, narrators = emptyList(), chapterCount = null, year = null, format = null),
                steps = emptyList(),
                candidates = listOf(candidate),
                sources = emptyList(),
                region = null,
            )

        /** A real kRPC server, fronted on the authed mount by a relay that can age the client. */
        class RelayedServer(
            service: MatchingService,
        ) {
            val sendFutureLiteral = AtomicBoolean(false)
            private val relayClient = HttpClient(OkHttp) { install(WebSockets) }

            val server: EmbeddedServer<*, *> =
                embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                    install(ServerKrpc)
                    routing {
                        serverRpc(REAL_MOUNT) {
                            rpcConfig { serialization { krpcJson(contractJson) } }
                            registerService<MatchingService> { service }
                        }
                        webSocket(RpcPolicy.Authed.mount) {
                            val toClient = this
                            relayClient.webSocket("ws://127.0.0.1:${port()}$REAL_MOUNT") {
                                val toServer = this
                                val upstream = launch { for (frame in toClient.incoming) toServer.send(frame.copy()) }
                                for (frame in toServer.incoming) {
                                    val aged =
                                        if (frame is Frame.Text && sendFutureLiteral.get()) {
                                            Frame.Text(frame.readText().replace(MatchTier.STRONG.name, FUTURE_TIER))
                                        } else {
                                            frame.copy()
                                        }
                                    toClient.send(aged)
                                }
                                upstream.cancel()
                            }
                        }
                    }
                }.start(wait = false)

            suspend fun port(): Int =
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port

            fun stop() {
                relayClient.close()
                server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            }
        }

        class FixedApiClientFactory(
            private val client: HttpClient,
        ) : ApiClientFactory {
            override suspend fun getClient(): HttpClient = client

            override suspend fun currentAccessToken(): String? = null

            override suspend fun invalidateRequestClientOnly() = Unit

            override suspend fun warmUp() = Unit

            override suspend fun invalidate() = Unit
        }

        test("an unknown literal in a strict field fails that one call with a typed error, and the channel lives on") {
            runBlocking {
                val service = mock<MatchingService>()
                everySuspend { service.findBookMatches(any(), any()) } returns AppResult.Success(found)
                val relayed = RelayedServer(service)
                val client = HttpClient(OkHttp) { install(WebSockets) }
                try {
                    val channel =
                        RpcChannel.forServer<MatchingService>(
                            apiClientFactory = FixedApiClientFactory(client),
                            serverConfig = TestServerConfig("http://127.0.0.1:${relayed.port()}"),
                        )

                    suspend fun find() = channel.call(idempotent = true) { it.findBookMatches(BookId("b1"), BookFindRequest()) }

                    // Control: untouched frames decode, so the relay is not what fails the next call.
                    find() shouldBe AppResult.Success(found)

                    relayed.sendFutureLiteral.set(true)
                    val error = find().shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<TransportError.DataMalformed>()
                    // What the user reads; the literal the decoder choked on stays in the developer-facing detail.
                    error.message shouldBe "Server response was malformed."
                    error.isRetryable shouldBe false
                    error.detail shouldContain FUTURE_TIER

                    relayed.sendFutureLiteral.set(false)
                    find() shouldBe AppResult.Success(found)
                } finally {
                    client.close()
                    relayed.stop()
                }
            }
        }
    })
