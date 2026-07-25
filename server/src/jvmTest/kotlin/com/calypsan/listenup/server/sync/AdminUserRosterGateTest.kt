package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.publicAuthService

import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.AdminUserRosterSyncPayload
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.rows
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.koin.ktor.ext.inject

/**
 * Proves the `admin_user_roster` sync domain is admin-only on catch-up — its rows carry
 * user email/role/status, so a plain member must never receive them; an admin sees them all.
 * Sibling to [LibraryFolderSyncAccessTest] (the other whole-domain, role-only gate).
 */
class AdminUserRosterGateTest :
    FunSpec({

        test("admin_user_roster catch-up returns the roster to an admin but nothing to a member") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val admin = mintRootToken()
                val member = registerMember()

                val roster by application.inject<AdminUserRosterRepository>()
                roster.upsert(rosterRowFixture("roster-user"))

                val adminPage =
                    authedService<SyncStreamService>(admin)
                        .pullDomain("admin_user_roster", since = 0, limit = 500)
                        .shouldSucceed()
                adminPage.rows(AdminUserRosterSyncPayload.serializer()).shouldNotBeEmpty()

                val memberPage =
                    authedService<SyncStreamService>(member)
                        .pullDomain("admin_user_roster", since = 0, limit = 500)
                        .shouldSucceed()
                memberPage.rows(AdminUserRosterSyncPayload.serializer()).shouldBeEmpty()
            }
        }
    })

private suspend fun ApplicationTestBuilder.mintRootToken(): String =
    publicAuthService()
        .setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
        .let { it as AppResult.Success<AuthSession> }
        .data.accessToken.value

private suspend fun ApplicationTestBuilder.registerMember(): String =
    publicAuthService()
        .register(RegisterRequest("member@x", "y".repeat(8), "Member"))
        .let { it as AppResult.Success<RegisterResult> }
        .data
        .let { it as RegisterResult.Authenticated }
        .session.accessToken.value

private fun rosterRowFixture(id: String): AdminUserRosterSyncPayload =
    AdminUserRosterSyncPayload(
        id = id,
        email = "$id@example.com",
        displayName = "Roster Fixture",
        role = "MEMBER",
        status = "ACTIVE",
        canShare = true,
        accountCreatedAt = 1_000L,
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
