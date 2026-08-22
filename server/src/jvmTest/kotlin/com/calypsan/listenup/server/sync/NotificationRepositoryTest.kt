@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.shouldFailWith
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Behaviour of [NotificationRepository]'s service-layer helpers, [NotificationRepository.markRead]
 * and [NotificationRepository.pruneToRetention]. Both route through the syncable substrate, so
 * every mutation bumps a revision (devices converge); markRead's ownership check fails closed —
 * a row that is missing, tombstoned, or someone else's is NotFound, never a cross-user write.
 * The user-scoping proofs live in [NotificationUserScopingTest].
 */
class NotificationRepositoryTest :
    FunSpec({

        test("markRead sets readAt and bumps the revision") {
            withSqlDatabase {
                val repo = notificationFixture()
                runTest {
                    repo.upsert(notificationPayload("n-1"), userId = "uA").shouldSucceed()
                    val before = sql.notificationsQueries.selectById("n-1").executeAsOne()
                    before.read_at shouldBe null

                    repo.markRead("n-1", userId = "uA", readAtMs = 123L).shouldSucceed()

                    val after = sql.notificationsQueries.selectById("n-1").executeAsOne()
                    after.read_at shouldBe 123L
                    after.revision shouldBeGreaterThan before.revision
                }
            }
        }

        test("markRead on another user's row is NotFound — ownership fails closed") {
            withSqlDatabase {
                val repo = notificationFixture()
                runTest {
                    repo.upsert(notificationPayload("n-1"), userId = "uA").shouldSucceed()

                    repo.markRead("n-1", userId = "uB", readAtMs = 123L).shouldFailWith<SyncError.NotFound>()

                    // The row is untouched — B's attempt neither read-marked nor bumped it.
                    sql.notificationsQueries
                        .selectById("n-1")
                        .executeAsOne()
                        .read_at shouldBe null
                }
            }
        }

        test("markRead on a missing or tombstoned row is NotFound") {
            withSqlDatabase {
                val repo = notificationFixture()
                runTest {
                    repo.markRead("n-ghost", userId = "uA", readAtMs = 123L).shouldFailWith<SyncError.NotFound>()

                    repo.upsert(notificationPayload("n-1"), userId = "uA").shouldSucceed()
                    repo.softDelete("n-1", userId = "uA").shouldSucceed()
                    repo.markRead("n-1", userId = "uA", readAtMs = 123L).shouldFailWith<SyncError.NotFound>()
                }
            }
        }

        test("markRead twice: the second call succeeds without a second revision bump") {
            withSqlDatabase {
                val repo = notificationFixture()
                runTest {
                    repo.upsert(notificationPayload("n-1"), userId = "uA").shouldSucceed()
                    repo.markRead("n-1", userId = "uA", readAtMs = 123L).shouldSucceed()
                    val first = sql.notificationsQueries.selectById("n-1").executeAsOne()

                    repo.markRead("n-1", userId = "uA", readAtMs = 456L).shouldSucceed()

                    val second = sql.notificationsQueries.selectById("n-1").executeAsOne()
                    second.revision shouldBe first.revision
                    // Idempotent: the original readAt stands — cross-device read state converges
                    // on the FIRST read, not the latest re-read.
                    second.read_at shouldBe 123L
                }
            }
        }

        test("pruneToRetention tombstones the oldest live rows beyond keep, per user") {
            withSqlDatabase {
                val clock = MutableClock(Instant.fromEpochMilliseconds(1_000_000L))
                val repo = notificationFixture(clock = clock)
                runTest {
                    // A's rows, created oldest → newest under the advancing clock.
                    for (i in 1..4) {
                        repo.upsert(notificationPayload("n-a$i"), userId = "uA").shouldSucceed()
                        clock.instant += 1.seconds
                    }
                    // B's row is older than everything of A's kept set — must never be pruned by A's sweep.
                    repo.upsert(notificationPayload("n-b1"), userId = "uB").shouldSucceed()

                    val pruned = repo.pruneToRetention("uA", keep = 2)
                    pruned shouldBe 2

                    // Oldest two tombstoned; newest two live; B untouched.
                    sql.notificationsQueries
                        .selectById("n-a1")
                        .executeAsOne()
                        .deleted_at shouldNotBe null
                    sql.notificationsQueries
                        .selectById("n-a2")
                        .executeAsOne()
                        .deleted_at shouldNotBe null
                    sql.notificationsQueries
                        .selectById("n-a3")
                        .executeAsOne()
                        .deleted_at shouldBe null
                    sql.notificationsQueries
                        .selectById("n-a4")
                        .executeAsOne()
                        .deleted_at shouldBe null
                    sql.notificationsQueries
                        .selectById("n-b1")
                        .executeAsOne()
                        .deleted_at shouldBe null

                    // At-or-below retention is a no-op.
                    repo.pruneToRetention("uA", keep = 2) shouldBe 0
                }
            }
        }
    })
