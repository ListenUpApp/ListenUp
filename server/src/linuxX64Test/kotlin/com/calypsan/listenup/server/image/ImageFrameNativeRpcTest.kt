package com.calypsan.listenup.server.image

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.api.dto.image.ImageFraming
import com.calypsan.listenup.api.dto.profile.Profile
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.ContributorId
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
 * Native (linuxX64) proof that a photo frame survives kotlinx.rpc on the server `server.kexe` runs. Kotlin/Native has
 * no reflection, so a frame type without a compiled serializer would fail here and nowhere on the JVM (#1605's class
 * of bug). The services are stubs: what is on trial is the transport decoding the call.
 */
class ImageFrameNativeRpcTest :
    FunSpec({
        test("a contributor frame and an avatar frame reach their services over native krpc") {
            val framing = ImageFraming("abc", ImageFrame(0.5, 0.25, 1.75))
            val contributorPatches = mutableListOf<ContributorUpdate>()
            val profileRequests = mutableListOf<UpdateProfileRequest>()
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
                            registerService<ContributorService> { RecordingContributors(contributorPatches) }
                            registerService<ProfileService> { RecordingProfiles(profileRequests) }
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
                    rpc.withService<ContributorService>().updateContributor(ContributorId("c1"), ContributorUpdate(imageFrame = framing))
                    rpc.withService<ProfileService>().updateMyProfile(UpdateProfileRequest(avatarFrame = framing))

                    contributorPatches.map { it.imageFrame } shouldBe listOf(framing)
                    profileRequests.map { it.avatarFrame } shouldBe listOf(framing)
                } finally {
                    client.close()
                }
            } finally {
                server.stop(0, 0)
            }
        }
    })

/** Records each contributor patch and succeeds; nothing else is called. */
private class RecordingContributors(
    private val received: MutableList<ContributorUpdate>,
) : ContributorService {
    override suspend fun updateContributor(
        id: ContributorId,
        patch: ContributorUpdate,
    ): AppResult<Unit> {
        received += patch
        return AppResult.Success(Unit)
    }

    override suspend fun getContributor(id: ContributorId): AppResult<ContributorSyncPayload?> = unused()

    override suspend fun listBooksByContributor(id: ContributorId): AppResult<List<BookSyncPayload>> = unused()

    override suspend fun deleteContributor(id: ContributorId): AppResult<Unit> = unused()

    override suspend fun mergeContributors(
        source: ContributorId,
        target: ContributorId,
    ): AppResult<Unit> = unused()

    override suspend fun unmergeContributor(
        contributorId: ContributorId,
        aliasName: String,
    ): AppResult<ContributorId> = unused()

    private fun unused(): Nothing = error("not part of this test")
}

/** Records each profile update and answers a fixed profile. */
private class RecordingProfiles(
    private val received: MutableList<UpdateProfileRequest>,
) : ProfileService {
    override suspend fun updateMyProfile(request: UpdateProfileRequest): AppResult<Profile> {
        received += request
        return AppResult.Success(Profile(UserId("u1"), "Ada", updatedAt = 1))
    }

    override suspend fun getMyProfile(): AppResult<Profile> = error("not part of this test")
}
