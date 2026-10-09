package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.MatchingService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.BookMatchApply
import com.calypsan.listenup.api.dto.match.BookMatchReview
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.dto.match.PersonMatchApply
import com.calypsan.listenup.api.dto.match.PersonMatchReview
import com.calypsan.listenup.api.dto.match.UndoResult
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Mutated
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.foundation.FoundationDeps
import com.calypsan.listenup.server.foundation.foundationServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
 * Native (linuxX64) proof that a person Review's arguments decode on the server `server.kexe` runs. Kotlin/Native
 * has no reflection, so an argument type without a compiled serializer fails here and nowhere on the JVM.
 *
 * Regression: `role: ContributorRole?` had none, so every person Review failed on the server ("Couldn't load this
 * match") while every JVM test stayed green. The service is a stub: what is on trial is the transport decoding
 * the call, not matching.
 */
class PersonReviewNativeRpcTest :
    FunSpec({
        test("a person Review's role reaches the service over native krpc") {
            val received = mutableListOf<ContributorRole?>()
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
                            registerService<MatchingService> { RecordingMatching(received) }
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
                    val matching =
                        client
                            .rpc("ws://127.0.0.1:$port/api/rpc/public") {
                                rpcConfig { serialization { json(contractJson) } }
                            }.withService<MatchingService>()

                    val review =
                        matching.reviewPersonMatch(
                            contributorId = ContributorId("someone"),
                            candidate = PersonCandidateKey(listOf(ExternalRef(ExternalRef.AUDIBLE, "B001H6QR1C"))),
                            role = ContributorRole.NARRATOR,
                        )

                    review.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<MetadataError.NotFound>()
                    received shouldBe listOf(ContributorRole.NARRATOR)
                } finally {
                    client.close()
                }
            } finally {
                server.stop(0, 0)
            }
        }
    })

/** Records each person Review's role and answers NotFound; nothing else is called. */
private class RecordingMatching(
    private val received: MutableList<ContributorRole?>,
) : MatchingService {
    override suspend fun reviewPersonMatch(
        contributorId: ContributorId,
        candidate: PersonCandidateKey,
        role: ContributorRole?,
    ): AppResult<PersonMatchReview> {
        received += role
        return AppResult.Failure(MetadataError.NotFound())
    }

    override suspend fun findBookMatches(
        bookId: BookId,
        request: BookFindRequest,
    ): AppResult<BookFindResult> = unused()

    override suspend fun findPeople(
        contributorId: ContributorId,
        request: PersonFindRequest,
    ): AppResult<PersonFindResult> = unused()

    override suspend fun reviewBookMatch(
        bookId: BookId,
        candidate: BookCandidateKey,
        region: MetadataLocale?,
    ): AppResult<BookMatchReview> = unused()

    override suspend fun applyBookMatch(
        bookId: BookId,
        request: BookMatchApply,
    ): AppResult<Mutated<MatchReceipt>> = unused()

    override suspend fun applyPersonMatch(
        contributorId: ContributorId,
        request: PersonMatchApply,
    ): AppResult<Mutated<MatchReceipt>> = unused()

    override suspend fun undoMatch(receiptId: String): AppResult<Mutated<UndoResult>> = unused()

    private fun unused(): Nothing = error("not part of this test")
}
