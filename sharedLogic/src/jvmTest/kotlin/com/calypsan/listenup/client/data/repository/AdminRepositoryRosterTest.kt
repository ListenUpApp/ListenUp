package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.client.data.local.db.AdminUserRosterEntity
import com.calypsan.listenup.client.data.remote.InviteRpcFactory
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.UserPermissions
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Verifies [AdminRepositoryImpl.observeRoster] maps synced [AdminUserRosterEntity] rows
 * (from the Room-backed `admin_user_roster` sync domain) to [AdminUserInfo], with a real
 * in-memory Room database — no fakes for the DAO layer.
 */
class AdminRepositoryRosterTest :
    FunSpec({

        test("observeRoster maps roster rows to AdminUserInfo, isRoot true only for the ROOT row") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    db.adminUserRosterDao().upsert(
                        AdminUserRosterEntity(
                            id = "user-1",
                            email = "member@example.com",
                            displayName = "Member User",
                            role = "MEMBER",
                            status = "ACTIVE",
                            canShare = true,
                            accountCreatedAt = 1_000L,
                            revision = 1L,
                        ),
                    )
                    db.adminUserRosterDao().upsert(
                        AdminUserRosterEntity(
                            id = "user-2",
                            email = "root@example.com",
                            displayName = "Root User",
                            role = "ROOT",
                            status = "ACTIVE",
                            canShare = false,
                            accountCreatedAt = 2_000L,
                            revision = 1L,
                        ),
                    )

                    val repo =
                        AdminRepositoryImpl(
                            adminUserChannel = RpcChannel.forTest(mock<AdminUserService>()),
                            adminSettingsChannel = RpcChannel.forTest(mock<AdminSettingsService>()),
                            inviteRpc = mock<InviteRpcFactory>(),
                            libraryAdminChannel = RpcChannel.forTest(mock<LibraryAdminService>()),
                            serverConfig = mock<ServerConfig>(),
                            adminUserRosterDao = db.adminUserRosterDao(),
                        )

                    val roster = repo.observeRoster().first()

                    roster.shouldContainExactlyInAnyOrder(
                        AdminUserInfo(
                            id = "user-1",
                            email = "member@example.com",
                            displayName = "Member User",
                            firstName = null,
                            lastName = null,
                            isRoot = false,
                            role = "MEMBER",
                            status = "ACTIVE",
                            permissions = UserPermissions(canShare = true),
                            createdAt = "1000",
                        ),
                        AdminUserInfo(
                            id = "user-2",
                            email = "root@example.com",
                            displayName = "Root User",
                            firstName = null,
                            lastName = null,
                            isRoot = true,
                            role = "ROOT",
                            status = "ACTIVE",
                            permissions = UserPermissions(canShare = false),
                            createdAt = "2000",
                        ),
                    )
                } finally {
                    db.close()
                }
            }
        }
    })
