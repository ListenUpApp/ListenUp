package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.auth.AdminUserPatch
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.PendingRegistrationOutcome
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserPermissions
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.IOException

// ─── Fakes ────────────────────────────────────────────────────────────────────

private fun testUser(
    id: String = "u1",
    role: UserRole = UserRole.MEMBER,
    status: UserStatus = UserStatus.ACTIVE,
) = User(
    id = UserId(id),
    email = "$id@example.com",
    displayName = "User $id",
    role = role,
    status = status,
    createdAt = 1_000_000L,
    permissions = UserPermissions(canEdit = true, canShare = true),
)

private class FakeAdminUserService : AdminUserService {
    private val users = mutableMapOf<String, User>()

    var lastDecision: PendingRegistrationDecision? = null
    var lastPatch: AdminUserPatch? = null
    val deletedIds = mutableListOf<String>()

    fun seedUser(user: User) {
        users[user.id.value] = user
    }

    override suspend fun listUsers(): AppResult<List<User>> = AppResult.Success(users.values.toList())

    override suspend fun listPendingUsers(): AppResult<List<User>> = AppResult.Success(users.values.filter { it.status == UserStatus.PENDING_APPROVAL })

    override suspend fun getUser(id: UserId): AppResult<User> {
        val user =
            users[id.value] ?: return AppResult.Failure(
                com.calypsan.listenup.api.error
                    .InternalError(debugInfo = "not found: ${id.value}"),
            )
        return AppResult.Success(user)
    }

    override suspend fun searchUsers(query: String): AppResult<List<User>> = AppResult.Success(users.values.filter { it.email.contains(query) || it.displayName.contains(query) })

    override suspend fun updateUser(
        id: UserId,
        patch: AdminUserPatch,
    ): AppResult<User> {
        lastPatch = patch
        val existing =
            users[id.value] ?: return AppResult.Failure(
                com.calypsan.listenup.api.error
                    .InternalError(debugInfo = "not found: ${id.value}"),
            )
        val updated =
            existing.copy(
                role = patch.role ?: existing.role,
                permissions = patch.permissions ?: existing.permissions,
            )
        users[id.value] = updated
        return AppResult.Success(updated)
    }

    override suspend fun deleteUser(id: UserId): AppResult<Unit> {
        deletedIds += id.value
        users.remove(id.value)
        return AppResult.Success(Unit)
    }

    override suspend fun decidePendingRegistration(
        request: PendingRegistrationDecision,
    ): AppResult<PendingRegistrationOutcome> {
        lastDecision = request
        val user =
            users[request.userId.value]
                ?: return AppResult.Failure(
                    com.calypsan.listenup.api.error
                        .InternalError(debugInfo = "not found: ${request.userId.value}"),
                )
        val newStatus = if (request.approved) UserStatus.ACTIVE else UserStatus.DENIED
        users[request.userId.value] = user.copy(status = newStatus)
        return AppResult.Success(
            if (request.approved) PendingRegistrationOutcome.Approved else PendingRegistrationOutcome.Denied,
        )
    }

    override suspend fun getRegistrationPolicy(): AppResult<RegistrationPolicy> = AppResult.Success(RegistrationPolicy.OPEN)

    var lastSetPolicy: RegistrationPolicy? = null

    override suspend fun setRegistrationPolicy(policy: RegistrationPolicy): AppResult<Unit> {
        lastSetPolicy = policy
        return AppResult.Success(Unit)
    }
}

// ─── Tests ────────────────────────────────────────────────────────────────────

class AdminRepositoryImplUserTest :
    FunSpec({

        fun buildRepo(service: AdminUserService): AdminRepositoryImpl =
            AdminRepositoryImpl(
                adminUserChannel = RpcChannel.forTest(service),
                adminSettingsChannel = RpcChannel.forTest(mock<AdminSettingsService>()),
                inviteRpc = mock<com.calypsan.listenup.client.data.remote.InviteRpcFactory>(),
                libraryAdminChannel = RpcChannel.forTest(mock<LibraryAdminService>()),
                serverConfig = mock<com.calypsan.listenup.client.domain.repository.ServerConfig>(),
                adminUserRosterDao = mock(),
            )

        test("getUsers maps contract Users to AdminUserInfo, ROOT user has isRoot=true") {
            val service = FakeAdminUserService()
            service.seedUser(testUser("u1", role = UserRole.ROOT))
            service.seedUser(testUser("u2", role = UserRole.MEMBER))
            val repo = buildRepo(service)

            val result = repo.getUsers()

            (result is AppResult.Success) shouldBe true
            val infos = (result as AppResult.Success).data
            infos.size shouldBe 2
            val root = infos.first { it.id == "u1" }
            root.isRoot shouldBe true
            root.role shouldBe "ROOT"
            val member = infos.first { it.id == "u2" }
            member.isRoot shouldBe false
            member.role shouldBe "MEMBER"
        }

        test("getPendingUsers returns only PENDING_APPROVAL users") {
            val service = FakeAdminUserService()
            service.seedUser(testUser("active1", status = UserStatus.ACTIVE))
            service.seedUser(testUser("pending1", status = UserStatus.PENDING_APPROVAL))
            service.seedUser(testUser("pending2", status = UserStatus.PENDING_APPROVAL))
            val repo = buildRepo(service)

            val result = repo.getPendingUsers()

            (result is AppResult.Success) shouldBe true
            val infos = (result as AppResult.Success).data
            infos.size shouldBe 2
            infos.all { it.status == "PENDING_APPROVAL" } shouldBe true
        }

        test("approveUser records approved=true decision and returns user with ACTIVE status") {
            val service = FakeAdminUserService()
            service.seedUser(testUser("u1", status = UserStatus.PENDING_APPROVAL))
            val repo = buildRepo(service)

            val result = repo.approveUser("u1")

            // Assert the decision that was recorded
            val decision = service.lastDecision
            decision?.userId shouldBe UserId("u1")
            decision?.approved shouldBe true

            // Assert the returned domain model has ACTIVE status
            (result is AppResult.Success) shouldBe true
            val info = (result as AppResult.Success).data
            info.id shouldBe "u1"
            info.status shouldBe "ACTIVE"
        }

        test("denyUser records approved=false decision") {
            val service = FakeAdminUserService()
            service.seedUser(testUser("u2", status = UserStatus.PENDING_APPROVAL))
            val repo = buildRepo(service)

            val result = repo.denyUser("u2")

            val decision = service.lastDecision
            decision?.userId shouldBe UserId("u2")
            decision?.approved shouldBe false
            (result is AppResult.Success) shouldBe true
        }

        test("deleteUser forwards deleteUser(UserId(id)) to the service") {
            val service = FakeAdminUserService()
            service.seedUser(testUser("u3"))
            val repo = buildRepo(service)

            val result = repo.deleteUser("u3")

            (result is AppResult.Success) shouldBe true
            service.deletedIds shouldBe listOf("u3")
        }

        test("updateUser sets role + canShare, preserves canEdit, never sends displayName") {
            val service = FakeAdminUserService()
            // Seed canEdit = false to prove it is preserved, not reset to the default true.
            service.seedUser(
                testUser("u4", role = UserRole.MEMBER).copy(
                    permissions = UserPermissions(canEdit = false, canShare = true),
                ),
            )
            val repo = buildRepo(service)

            val result =
                repo.updateUser(
                    userId = "u4",
                    firstName = "Alice",
                    lastName = "Smith",
                    role = "ADMIN",
                    canShare = false,
                )

            (result is AppResult.Success) shouldBe true
            val patch = service.lastPatch
            // displayName must NOT be sent — no contract field for first/last name
            patch?.displayName shouldBe null
            patch?.role shouldBe UserRole.ADMIN
            patch?.permissions?.canShare shouldBe false
            // canEdit preserved via read-before-write (server applies permissions wholesale)
            patch?.permissions?.canEdit shouldBe false

            val info = (result as AppResult.Success).data
            info.role shouldBe "ADMIN"
            info.permissions.canShare shouldBe false
        }

        test("getRegistrationPolicy returns the full policy from the service") {
            val service = FakeAdminUserService()
            val repo = buildRepo(service)

            val result = repo.getRegistrationPolicy()

            result shouldBe AppResult.Success(RegistrationPolicy.OPEN)
        }

        test("a transport throw becomes a typed AppResult.Failure (channel self-heals; no global invalidation)") {
            // The migrated AdminUserService rides an RpcChannel: a transport fault folds to a TYPED
            // TransportError via ErrorMapper, and the channel heals its OWN connection — there is no
            // global RpcCacheInvalidator sweep on this path anymore (self-heal #619 lives in the engine).
            val throwing =
                object : AdminUserService by mock<AdminUserService>() {
                    override suspend fun listUsers(): AppResult<List<User>> = throw IOException("simulated WS drop")
                }

            buildRepo(throwing)
                .getUsers()
                .shouldBeInstanceOf<AppResult.Failure>()
                .error
                .shouldBeInstanceOf<TransportError.NetworkUnavailable>()
        }

        test("setRegistrationPolicy(OPEN) sets policy OPEN") {
            val service = FakeAdminUserService()
            val repo = buildRepo(service)
            val result = repo.setRegistrationPolicy(RegistrationPolicy.OPEN)
            (result is AppResult.Success) shouldBe true
            service.lastSetPolicy shouldBe RegistrationPolicy.OPEN
        }

        test("setRegistrationPolicy(APPROVAL_QUEUE) round-trips the approval-queue policy") {
            val service = FakeAdminUserService()
            val repo = buildRepo(service)
            val result = repo.setRegistrationPolicy(RegistrationPolicy.APPROVAL_QUEUE)
            (result is AppResult.Success) shouldBe true
            service.lastSetPolicy shouldBe RegistrationPolicy.APPROVAL_QUEUE
        }

        test("setRegistrationPolicy(CLOSED) sets policy CLOSED") {
            val service = FakeAdminUserService()
            val repo = buildRepo(service)
            val result = repo.setRegistrationPolicy(RegistrationPolicy.CLOSED)
            (result is AppResult.Success) shouldBe true
            service.lastSetPolicy shouldBe RegistrationPolicy.CLOSED
        }

        test("setRegistrationPolicy returns a typed Failure (never throws) when the RPC transport throws") {
            val throwing =
                object : AdminUserService by mock<AdminUserService>() {
                    override suspend fun setRegistrationPolicy(policy: RegistrationPolicy): AppResult<Unit> = throw IOException("WS drop")
                }

            buildRepo(throwing)
                .setRegistrationPolicy(RegistrationPolicy.OPEN)
                .shouldBeInstanceOf<AppResult.Failure>()
                .error
                .shouldBeInstanceOf<TransportError.NetworkUnavailable>()
        }
    })
