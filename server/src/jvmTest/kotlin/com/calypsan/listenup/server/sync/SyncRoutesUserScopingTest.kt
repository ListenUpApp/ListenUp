package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.domainFrames
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.rpcFirehose
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first

/**
 * Pins the per-user awareness of the sync surface ([syncRoutes] + the RPC firehose):
 *
 *  - (a) the catch-up route for a user-scoped domain returns only the
 *    authenticated caller's rows;
 *  - (b) the RPC firehose delivers a user-scoped event to its owning user and
 *    withholds it from a different user;
 *  - (c) a *global* domain's catch-up and firehose are unaffected — every
 *    authenticated user sees every global row and every global event.
 *
 * The test harness authenticates the bearer token verbatim as the user id, so
 * `bearerAuth("u1")` is "request as user u1"; the firehose equivalent is
 * [rpcFirehose] over the harness bus as `rootPrincipal("u1")` (the harness's
 * default role for a bearer token is ROOT).
 */
class SyncRoutesUserScopingTest :
    FunSpec({

        test("(a) user-scoped catch-up returns only the caller's rows") {
            withTestApplication(userScoped = true) {
                userScopedRepo.upsert(UserScopedPayload(id = "a", label = "alpha"), userId = "u1")
                userScopedRepo.upsert(UserScopedPayload(id = "b", label = "beta"), userId = "u1")
                userScopedRepo.upsert(UserScopedPayload(id = "c", label = "gamma"), userId = "u2")

                val u1Response =
                    client.get("/api/v1/sync/user_scoped_fixtures?since=0") { bearerAuth("u1") }
                u1Response.status shouldBe HttpStatusCode.OK
                val u1Page: Page<UserScopedPayload> = u1Response.body()
                u1Page.items.map { it.id } shouldContainExactlyInAnyOrder listOf("a", "b")

                val u2Page: Page<UserScopedPayload> =
                    client.get("/api/v1/sync/user_scoped_fixtures?since=0") { bearerAuth("u2") }.body()
                u2Page.items.map { it.id } shouldContainExactlyInAnyOrder listOf("c")
            }
        }

        test("(b) firehose delivers a user-scoped event to its owner, not to another user") {
            withTestApplication(userScoped = true) {
                // u2's write must be skipped for the u1 subscriber; u1's write is delivered.
                // The bus's replay buffer holds both writes, so subscribing afterwards is
                // deterministic — the frames replay in publish order.
                userScopedRepo.upsert(UserScopedPayload(id = "other", label = "u2-row"), userId = "u2")
                userScopedRepo.upsert(UserScopedPayload(id = "mine", label = "u1-row"), userId = "u1")

                val frame =
                    rpcFirehose(bus, rootPrincipal("u1"))
                        .domainFrames()
                        .first { it.domain == "user_scoped_fixtures" }
                // The first user_scoped_fixtures frame the u1 stream sees is u1's own row,
                // never u2's — a leaked u2 event would arrive first and fail this.
                frame.json.contains(""""id":"mine"""") shouldBe true
            }
        }

        test("(c) global domain catch-up and firehose are unaffected by user scoping") {
            withTestApplication(userScoped = true) {
                tagRepo.upsert(Tag("a", "alpha", "alpha", 0, 0))
                tagRepo.upsert(Tag("b", "beta", "beta", 0, 0))

                // Catch-up: both users see every global row.
                val u1Tags: Page<Tag> =
                    client.get("/api/v1/sync/tags?since=0") { bearerAuth("u1") }.body()
                u1Tags.items.map { it.id } shouldContainExactlyInAnyOrder listOf("a", "b")
                val u2Tags: Page<Tag> =
                    client.get("/api/v1/sync/tags?since=0") { bearerAuth("u2") }.body()
                u2Tags.items shouldHaveSize 2

                // Firehose: a global event reaches a user who did not write it.
                // The replay buffer also holds the tags:a / tags:b events, so the
                // stream is filtered for the gamma write specifically.
                tagRepo.upsert(Tag("c", "gamma", "gamma", 0, 0))
                val frame =
                    rpcFirehose(bus, rootPrincipal("u2"))
                        .domainFrames()
                        .first { it.domain == "tags" && it.json.contains(""""name":"gamma"""") }
                frame.domain shouldBe "tags"
            }
        }
    })
