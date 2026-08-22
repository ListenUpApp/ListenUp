@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.server.testing.domainFrames
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rpcFirehose
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest

/**
 * The privacy boundary of the `notifications` sync domain: a notification exists for exactly one
 * user, and no read surface — catch-up pull, live firehose, digest, or targeted-by-id fetch — may
 * ever hand one user another user's row. These tests ARE that boundary's proof: each drives the
 * REAL [NotificationRepository] over a migrated SQLite database and would fail if the
 * `user_id` scoping dropped out of any path.
 */
class NotificationUserScopingTest :
    FunSpec({

        test("pullSince for user A returns only A's rows") {
            withSqlDatabase {
                val repo = notificationFixture()
                runTest {
                    repo.upsert(notificationPayload("n-a1"), userId = "uA").shouldSucceed()
                    repo.upsert(notificationPayload("n-a2"), userId = "uA").shouldSucceed()
                    repo.upsert(notificationPayload("n-b1"), userId = "uB").shouldSucceed()

                    val aPage = repo.pullSince("uA", cursor = 0L, limit = 100, extraWhere = null)
                    aPage.items.map { it.id } shouldContainExactlyInAnyOrder listOf("n-a1", "n-a2")
                    aPage.items.map { it.id } shouldNotContain "n-b1"

                    val bPage = repo.pullSince("uB", cursor = 0L, limit = 100, extraWhere = null)
                    bPage.items.map { it.id } shouldContainExactlyInAnyOrder listOf("n-b1")
                }
            }
        }

        test("firehose delivers A's row to A's stream and never to B's stream") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = notificationFixture(bus)
                runTest {
                    // Record all writes FIRST so the bus's replay buffer serves each subscriber
                    // deterministically, in publish order. B's row goes first: if the per-user
                    // scoping leaked, A's stream would see it before A's own row and fail below.
                    // B's sentinel goes last to bound B's stream read.
                    repo.upsert(notificationPayload("n-b-other"), userId = "uB").shouldSucceed()
                    repo.upsert(notificationPayload("n-a-mine"), userId = "uA").shouldSucceed()
                    repo.upsert(notificationPayload("n-b-sentinel"), userId = "uB").shouldSucceed()

                    val aFrame =
                        rpcFirehose(bus, memberPrincipal("uA"))
                            .domainFrames()
                            .first { it.domain == "notifications" }
                    aFrame.json.contains(""""id":"n-a-mine"""") shouldBe true

                    val bFrames = mutableListOf<SyncFrame>()
                    rpcFirehose(bus, memberPrincipal("uB"))
                        .domainFrames()
                        .filter { it.domain == "notifications" }
                        .onEach { bFrames += it }
                        .first { it.json.contains("n-b-sentinel") }
                    // B saw their own rows up to the sentinel — and NEVER A's.
                    bFrames.any { it.json.contains(""""id":"n-b-other"""") } shouldBe true
                    bFrames.none { it.json.contains("n-a-mine") } shouldBe true
                }
            }
        }

        test("digest for A counts only A's live rows") {
            withSqlDatabase {
                val repo = notificationFixture()
                runTest {
                    repo.upsert(notificationPayload("n-a1"), userId = "uA").shouldSucceed()
                    repo.upsert(notificationPayload("n-a2"), userId = "uA").shouldSucceed()
                    repo.upsert(notificationPayload("n-b1"), userId = "uB").shouldSucceed()

                    // High enough to cover every seeded revision.
                    val cursor = 100L
                    val aDigest = repo.digest("uA", cursor, extraWhere = null)
                    val bDigest = repo.digest("uB", cursor, extraWhere = null)

                    aDigest.count shouldBe 2
                    bDigest.count shouldBe 1
                    aDigest.hash shouldNotBe bDigest.hash
                }
            }
        }

        test("pullByIds as A returns an EMPTY page — the userScoped short-circuit") {
            withSqlDatabase {
                val repo = notificationFixture()
                runTest {
                    repo.upsert(notificationPayload("n-a1"), userId = "uA").shouldSucceed()
                    repo.upsert(notificationPayload("n-b1"), userId = "uB").shouldSucceed()

                    // PIN: a userScoped domain with no wired driver answers `?ids=` with an EMPTY
                    // page BY CONSTRUCTION — even for ids that exist, even the caller's own. This
                    // is the leak-proof property the notifications domain relies on: there is no
                    // per-row access filter to get wrong, because the targeted-fetch path simply
                    // cannot serve rows. Convergence rides `?since=` (the user-scoped pullSince).
                    val page =
                        repo.pullByIds(
                            userId = "uA",
                            matchColumn = "id",
                            matchValues = listOf("n-b1", "n-a1"),
                            extraWhere = null,
                        )
                    page.items.shouldBeEmpty()
                    page.hasMore shouldBe false
                }
            }
        }

        test("a tombstoned row still reaches its owner's pull") {
            withSqlDatabase {
                val repo = notificationFixture()
                runTest {
                    repo.upsert(notificationPayload("n-a1"), userId = "uA").shouldSucceed()
                    repo.upsert(notificationPayload("n-a2"), userId = "uA").shouldSucceed()
                    repo.softDelete("n-a1", userId = "uA").shouldSucceed()

                    val page = repo.pullSince("uA", cursor = 0L, limit = 100, extraWhere = null)
                    val tombstone = page.items.first { it.id == "n-a1" }
                    tombstone.deletedAt shouldNotBe null
                    page.items.first { it.id == "n-a2" }.deletedAt shouldBe null
                }
            }
        }
    })
