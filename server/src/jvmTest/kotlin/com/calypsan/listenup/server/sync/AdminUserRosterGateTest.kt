package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.AdminUserRosterSyncPayload
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
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
                val client = jsonClient()
                val admin = client.mintRootToken()
                val member = client.registerMember()

                val roster by application.inject<AdminUserRosterRepository>()
                roster.upsert(rosterRowFixture("roster-user"))

                val adminPage: Page<AdminUserRosterSyncPayload> =
                    client.get("/api/v1/sync/admin_user_roster?since=0") { bearerAuth(admin) }.body()
                adminPage.items.shouldNotBeEmpty()

                val memberPage: Page<AdminUserRosterSyncPayload> =
                    client.get("/api/v1/sync/admin_user_roster?since=0") { bearerAuth(member) }.body()
                memberPage.items.shouldBeEmpty()
            }
        }
    })

private fun ApplicationTestBuilder.jsonClient(): HttpClient =
    createClient {
        install(ContentNegotiation) { json(contractJson) }
    }

private suspend fun HttpClient.mintRootToken(): String =
    post("/api/v1/auth/setup") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
    }.body<AppResult<AuthSession>>()
        .let { it as AppResult.Success<AuthSession> }
        .data.accessToken.value

private suspend fun HttpClient.registerMember(): String =
    post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("member@x", "y".repeat(8), "Member"))
    }.body<AppResult<RegisterResult>>()
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
