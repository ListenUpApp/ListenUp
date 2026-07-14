@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class MetadataCacheCleanupTaskTest :
    FunSpec({

        val now = Instant.parse("2026-05-24T12:00:00Z")

        test("runOnce deletes expired rows only; fresh rows survive") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                // expired: expiresAt is 1 second in the past
                val expiredAt = now.toEpochMilliseconds() - 1_000L
                // fresh: expiresAt is 1 hour in the future
                val freshAt = now.plus(1.hours).toEpochMilliseconds()
                runTest {
                    repo.put(MetadataProviderId.AUDIBLE, "us", "expired-key", "{}", expiredAt)
                    repo.put(MetadataProviderId.AUDIBLE, "us", "fresh-key", "{}", freshAt)

                    val task = MetadataCacheCleanupTask(repo, clock = FixedClock(now))
                    val removed = task.runOnce()

                    removed shouldBe 1
                    // lazy-eviction get also uses the now clock, but the task already deleted it
                    // so a direct deleteExpired check is the cleaner assertion
                    repo.get(MetadataProviderId.AUDIBLE, "us", "fresh-key").shouldNotBeNull()
                }
            }
        }

        test("runOnce on an empty table returns 0 without throwing") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                runTest {
                    val removed = MetadataCacheCleanupTask(repo, clock = FixedClock(now)).runOnce()
                    removed shouldBe 0
                }
            }
        }

        test("runOnce with only fresh rows returns 0") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                val freshAt = now.plus(24.hours).toEpochMilliseconds()
                runTest {
                    repo.put(MetadataProviderId.AUDIBLE, "us", "k1", "a", freshAt)
                    repo.put(MetadataProviderId.AUDIBLE, "uk", "k2", "b", freshAt)

                    val removed = MetadataCacheCleanupTask(repo, clock = FixedClock(now)).runOnce()
                    removed shouldBe 0
                    repo.get(MetadataProviderId.AUDIBLE, "us", "k1").shouldNotBeNull()
                    repo.get(MetadataProviderId.AUDIBLE, "uk", "k2").shouldNotBeNull()
                }
            }
        }

        test("runOnce deletes all rows when all are expired") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                val expiredAt = now.toEpochMilliseconds() - 5_000L
                runTest {
                    repo.put(MetadataProviderId.AUDIBLE, "us", "a", "x", expiredAt)
                    repo.put(MetadataProviderId.AUDIBLE, "us", "b", "y", expiredAt)
                    repo.put(MetadataProviderId.AUDIBLE, "uk", "c", "z", expiredAt)

                    val removed = MetadataCacheCleanupTask(repo, clock = FixedClock(now)).runOnce()
                    removed shouldBe 3
                }
            }
        }
    })
