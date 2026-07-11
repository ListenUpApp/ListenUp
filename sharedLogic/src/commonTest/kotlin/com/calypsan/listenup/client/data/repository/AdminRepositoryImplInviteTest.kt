package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.InviteService
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.invite.InviteDto
import com.calypsan.listenup.api.dto.invite.InviteId
import com.calypsan.listenup.api.dto.invite.InviteSummary
import com.calypsan.listenup.api.dto.invite.InviteStatus
import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.remote.forTestScripted
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.client.domain.repository.ServerConfig
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ─── Fakes ────────────────────────────────────────────────────────────────────

private fun testInviteDto(
    id: String = "inv1",
    code: String = "code-abc",
    email: String = "ada@x",
    displayName: String = "Ada",
    role: UserRole = UserRole.ADMIN,
    expiresAt: Long = 9_000_000L,
    createdAt: Long = 1_000_000L,
    claimedAt: Long? = null,
    claimedBy: String? = null,
) = InviteDto(
    id = InviteId(id),
    code = code,
    email = email,
    displayName = displayName,
    role = role,
    createdBy = "root",
    expiresAt = expiresAt,
    createdAt = createdAt,
    claimedAt = claimedAt,
    claimedBy = claimedBy,
)

private class FakeInviteService : InviteService {
    val createdInvites = mutableListOf<Triple<String, String, UserRole>>()
    val revokedIds = mutableListOf<String>()

    private val invites = mutableListOf<InviteSummary>()

    fun seedSummary(summary: InviteSummary) {
        invites += summary
    }

    override suspend fun listInvites(): AppResult<List<InviteSummary>> = AppResult.Success(invites.toList())

    override suspend fun createInvite(
        email: String,
        displayName: String,
        role: UserRole,
        expiresInDays: Int?,
    ): AppResult<InviteDto> {
        createdInvites += Triple(email, displayName, role)
        return AppResult.Success(
            testInviteDto(email = email, displayName = displayName, role = role),
        )
    }

    override suspend fun revokeInvite(id: InviteId): AppResult<Unit> {
        revokedIds += id.value
        return AppResult.Success(Unit)
    }
}

private class FakeServerConfig(
    private val url: String = "https://srv.example",
) : ServerConfig {
    override val activeUrl: StateFlow<ServerUrl?> = MutableStateFlow(ServerUrl(url))

    override suspend fun setServerUrl(url: ServerUrl) = Unit

    override suspend fun getServerUrl(): ServerUrl? = ServerUrl(url)

    override suspend fun hasServerConfigured(): Boolean = true

    override suspend fun setRemoteUrl(url: String?) = Unit

    override suspend fun getRemoteUrl(): ServerUrl? = null

    override suspend fun getActiveUrl(): ServerUrl? = ServerUrl(url)

    override suspend fun switchToFallbackUrl(): ServerUrl? = null

    override suspend fun setActiveUrl(url: ServerUrl) = Unit

    override suspend fun setConnectedServerId(id: String?) = Unit

    override suspend fun getConnectedServerId(): String? = null

    override suspend fun updateLocalUrl(url: ServerUrl) = Unit

    override suspend fun disconnectFromServer() = Unit

    override suspend fun clearAll() = Unit
}

// ─── Tests ────────────────────────────────────────────────────────────────────

class AdminRepositoryImplInviteTest :
    FunSpec({

        fun buildRepo(
            service: FakeInviteService,
            serverUrl: String = "https://srv.example",
        ): AdminRepositoryImpl =
            AdminRepositoryImpl(
                adminUserChannel = RpcChannel.forTest(mock<AdminUserService>()),
                adminSettingsChannel = RpcChannel.forTest(mock<AdminSettingsService>()),
                inviteAdminChannel = RpcChannel.forTest(service),
                libraryAdminChannel = RpcChannel.forTest(mock<LibraryAdminService>()),
                serverConfig = FakeServerConfig(serverUrl),
                adminUserRosterDao = mock(),
            )

        test("getInvites maps InviteSummary list to InviteInfo with reconstructed url") {
            val service = FakeInviteService()
            service.seedSummary(
                InviteSummary(
                    invite = testInviteDto(id = "inv1", code = "abc123", displayName = "Ada"),
                    status = InviteStatus.PENDING,
                ),
            )
            val repo = buildRepo(service)

            val result = repo.getInvites()

            (result is AppResult.Success) shouldBe true
            val infos = (result as AppResult.Success).data
            infos.size shouldBe 1
            val info = infos.first()
            info.id shouldBe "inv1"
            info.code shouldBe "abc123"
            info.name shouldBe "Ada"
            info.url shouldBe "https://link.listenup.audio/o?t=invite&server=https%3A%2F%2Fsrv.example&code=abc123"
            info.claimedAt shouldBe null
        }

        test("getInvites maps claimedAt when claimed") {
            val service = FakeInviteService()
            service.seedSummary(
                InviteSummary(
                    invite = testInviteDto(id = "inv2", code = "xyz", claimedAt = 5_000_000L, claimedBy = "user1"),
                    status = InviteStatus.CLAIMED,
                ),
            )
            val repo = buildRepo(service)

            val result = repo.getInvites()

            (result is AppResult.Success) shouldBe true
            val info = (result as AppResult.Success).data.first()
            info.claimedAt shouldBe "5000000"
        }

        // Regression: the create-invite form emits lowercase role tokens ("member"/"admin"). The
        // previous `UserRole.valueOf(role)` threw IllegalArgumentException on those (it expects the
        // exact enum names), surfacing to the admin as "Something went wrong on the server".
        test("createInvite maps the lowercase 'admin' role token to UserRole.ADMIN") {
            val service = FakeInviteService()
            val repo = buildRepo(service)

            val result = repo.createInvite(email = "ada@example.com", role = "admin", expiresInDays = 7)

            (result is AppResult.Success) shouldBe true
            service.createdInvites.size shouldBe 1
            val (email, displayName, role) = service.createdInvites.first()
            email shouldBe "ada@example.com"
            role shouldBe UserRole.ADMIN
            // The admin no longer names the invitee — display name defaults to the email's local part.
            displayName shouldBe "ada"
            val info = (result as AppResult.Success).data
            info.email shouldBe "ada@example.com"
            info.role shouldBe "ADMIN"
            info.url shouldBe "https://link.listenup.audio/o?t=invite&server=https%3A%2F%2Fsrv.example&code=code-abc"
        }

        test("createInvite maps the lowercase 'member' role token to UserRole.MEMBER (least privilege)") {
            val service = FakeInviteService()
            val repo = buildRepo(service)

            val result = repo.createInvite(email = "pat@example.com", role = "member", expiresInDays = 7)

            (result is AppResult.Success) shouldBe true
            val (_, displayName, role) = service.createdInvites.first()
            role shouldBe UserRole.MEMBER
            displayName shouldBe "pat"
        }

        test("deleteInvite calls revokeInvite with wrapped InviteId") {
            val service = FakeInviteService()
            val repo = buildRepo(service)

            val result = repo.deleteInvite("inv1")

            (result is AppResult.Success) shouldBe true
            service.revokedIds shouldBe listOf("inv1")
        }

        // The invite path now folds through the RpcChannel, not the deleted `catching` helper: a
        // transport fault surfaces as the TYPED error ErrorMapper produces, not a blanket
        // InternalError. Here a dropped socket (IOException) surfaces as NetworkUnavailable.
        test("a transport fault on the invite channel folds to a typed AppResult.Failure, not a throw") {
            val repo =
                AdminRepositoryImpl(
                    adminUserChannel = RpcChannel.forTest(mock<AdminUserService>()),
                    adminSettingsChannel = RpcChannel.forTest(mock<AdminSettingsService>()),
                    inviteAdminChannel =
                        RpcChannel.forTestScripted(
                            mock<InviteService>(),
                            faults = listOf(kotlinx.io.IOException("simulated socket drop")),
                        ),
                    libraryAdminChannel = RpcChannel.forTest(mock<LibraryAdminService>()),
                    serverConfig = FakeServerConfig(),
                    adminUserRosterDao = mock(),
                )

            val result = repo.getInvites()

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
        }
    })
