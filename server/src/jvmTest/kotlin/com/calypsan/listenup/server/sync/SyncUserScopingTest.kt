package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.domainFrames
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.rows
import com.calypsan.listenup.server.testing.rpcFirehose
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first

/**
 * Pins the per-user awareness of the sync surface
 * ([com.calypsan.listenup.api.SyncStreamService]'s pull surface + the RPC firehose):
 *
 *  - (a) `pullDomain` for a user-scoped domain returns only the authenticated
 *    caller's rows;
 *  - (b) the RPC firehose delivers a user-scoped event to its owning user and
 *    withholds it from a different user;
 *  - (c) a *global* domain's pull and firehose are unaffected — every
 *    authenticated user sees every global row and every global event.
 *
 * The test harness names the caller directly via `syncService(userId)`, so
 * `syncService("u1")` is "the pull surface as seen by user u1"; the firehose
 * equivalent is [rpcFirehose] over the harness bus as `rootPrincipal("u1")`
 * (the harness's default role for a named caller is ROOT).
 */
class SyncUserScopingTest :
    FunSpec({

        test("(a) user-scoped catch-up returns only the caller's rows") {
            withTestApplication(userScoped = true) {
                userScopedRepo.upsert(UserScopedPayload(id = "a", label = "alpha"), userId = "u1")
                userScopedRepo.upsert(UserScopedPayload(id = "b", label = "beta"), userId = "u1")
                userScopedRepo.upsert(UserScopedPayload(id = "c", label = "gamma"), userId = "u2")

                val u1Page =
                    syncService("u1").pullDomain("user_scoped_fixtures", since = 0, limit = 100).shouldSucceed()
                u1Page.rows(UserScopedPayload.serializer()).map { it.id } shouldContainExactlyInAnyOrder
                    listOf("a", "b")

                val u2Page =
                    syncService("u2").pullDomain("user_scoped_fixtures", since = 0, limit = 100).shouldSucceed()
                u2Page.rows(UserScopedPayload.serializer()).map { it.id } shouldContainExactlyInAnyOrder listOf("c")
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
                val u1Tags = syncService("u1").pullDomain("tags", since = 0, limit = 100).shouldSucceed()
                u1Tags.rows(Tag.serializer()).map { it.id } shouldContainExactlyInAnyOrder listOf("a", "b")
                val u2Tags = syncService("u2").pullDomain("tags", since = 0, limit = 100).shouldSucceed()
                u2Tags.rows(Tag.serializer()) shouldHaveSize 2

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
