@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Pins the [FirehoseSuppressed] gate on the [SqlSyncableRepository] write path —
 * the keystone of Stage 2 (onboarding firehose suppression).
 *
 * Under the marker, `upsert`/`softDelete` MUST still bump the revision and commit
 * the row (so REST `pullSince` catch-up sees it) but MUST NOT publish to the
 * [ChangeBus] live tail (so a bulk burst can't overflow `replay = 256` →
 * `CursorStale`). Without the marker, every write publishes as before.
 */
class SyncableRepositoryFirehoseSuppressionTest :
    FunSpec({

        test("upsert under FirehoseSuppressed bumps revision and commits the row but publishes no event") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = FixtureRepository(sql, driver, bus, SyncRegistry())
                repo.createSchema()

                runTest {
                    withContext(FirehoseSuppressed) {
                        repo.upsert(
                            FixturePayload(FixtureId("sup"), "suppressed", revision = 0, updatedAt = 0),
                            clientOpId = "op-sup",
                        )
                    }
                    advanceUntilIdle()

                    // No event reached the live tail.
                    bus.subscribe().replayCache.shouldBeEmpty()

                    // ...yet REST catch-up sees the committed row (revision was still bumped).
                    val page = repo.pullSince(userId = null, cursor = 0, limit = 10)
                    page.items.map { it.id.value } shouldContainExactly listOf("sup")
                }
            }
        }

        test("upsert without the marker publishes to the live tail as before") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = FixtureRepository(sql, driver, bus, SyncRegistry())
                repo.createSchema()

                runTest {
                    repo.upsert(
                        FixturePayload(FixtureId("norm"), "normal", revision = 0, updatedAt = 0),
                        clientOpId = "op-norm",
                    )
                    advanceUntilIdle()

                    bus.subscribe().replayCache.map { it.event.id } shouldContainExactly listOf("norm")
                }
            }
        }

        test("softDelete under FirehoseSuppressed tombstones the row but publishes no event") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = FixtureRepository(sql, driver, bus, SyncRegistry())
                repo.createSchema()

                runTest {
                    // Seed a row with the firehose live so we have a clean tail to assert against.
                    repo.upsert(
                        FixturePayload(FixtureId("gone"), "doomed", revision = 0, updatedAt = 0),
                        clientOpId = "op-seed",
                    )
                    advanceUntilIdle()
                    bus.subscribe().replayCache.map { it.event.id } shouldContainExactly listOf("gone")

                    withContext(FirehoseSuppressed) {
                        repo.softDelete(FixtureId("gone"), clientOpId = "op-del")
                    }
                    advanceUntilIdle()

                    // The Deleted event was suppressed — the tail still shows only the seed Created.
                    bus.subscribe().replayCache.map { it.event.id } shouldContainExactly listOf("gone")
                    bus.subscribe().replayCache.size shouldBe 1
                }
            }
        }
    })
