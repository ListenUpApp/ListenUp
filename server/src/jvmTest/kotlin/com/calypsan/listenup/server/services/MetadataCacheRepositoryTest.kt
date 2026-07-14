@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class MetadataCacheRepositoryTest :
    FunSpec({
        val now = Instant.parse("2026-05-24T12:00:00Z")

        test("get returns null for an unknown key") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                runTest {
                    repo.get("missing", AudibleRegion.US).shouldBeNull()
                }
            }
        }

        test("put then get round-trips the payload") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                val expiresAt = now.plus(24.hours).toEpochMilliseconds()
                runTest {
                    repo.put("search:us:harry potter", AudibleRegion.US, """{"results": []}""", expiresAt)
                    val cached = repo.get("search:us:harry potter", AudibleRegion.US)
                    cached shouldBe """{"results": []}"""
                }
            }
        }

        test("get for an expired row returns null AND the row is cleaned up") {
            withSqlDatabase {
                val clock = MutableClock(now)
                val repo = MetadataCacheRepository(sql, clock = clock)
                val expiresAt = now.toEpochMilliseconds() + 1_000L
                runTest {
                    repo.put("key1", AudibleRegion.US, "{}", expiresAt)
                    // Advance clock past expiry
                    clock.set(now.plus(2.hours))
                    repo.get("key1", AudibleRegion.US).shouldBeNull()
                    // Row was deleted — re-put with the same key works without a unique-constraint error
                    val newExpiry = clock.now().plus(1.hours).toEpochMilliseconds()
                    repo.put("key1", AudibleRegion.US, """{"new": true}""", newExpiry)
                    repo.get("key1", AudibleRegion.US) shouldBe """{"new": true}"""
                }
            }
        }

        test("get is region-scoped — same logical key in different regions is independent") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                val expiresAt = now.plus(1.hours).toEpochMilliseconds()
                runTest {
                    repo.put("k", AudibleRegion.US, "us-data", expiresAt)
                    repo.put("k", AudibleRegion.UK, "uk-data", expiresAt)
                    repo.get("k", AudibleRegion.US) shouldBe "us-data"
                    repo.get("k", AudibleRegion.UK) shouldBe "uk-data"
                }
            }
        }

        test("put replaces an existing entry for the same key+region") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                val expiresAt = now.plus(1.hours).toEpochMilliseconds()
                runTest {
                    repo.put("k", AudibleRegion.US, "original", expiresAt)
                    repo.put("k", AudibleRegion.US, "updated", expiresAt)
                    repo.get("k", AudibleRegion.US) shouldBe "updated"
                }
            }
        }

        test("deleteExpired removes only expired rows and returns the count") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                val expiredAt = now.toEpochMilliseconds() - 1_000L
                val freshAt = now.plus(1.hours).toEpochMilliseconds()
                runTest {
                    repo.put("a", AudibleRegion.US, "x", expiredAt)
                    repo.put("b", AudibleRegion.US, "y", expiredAt)
                    repo.put("c", AudibleRegion.US, "z", freshAt)
                    val removed = repo.deleteExpired(beforeMs = now.toEpochMilliseconds())
                    removed shouldBe 2
                    repo.get("a", AudibleRegion.US).shouldBeNull()
                    repo.get("b", AudibleRegion.US).shouldBeNull()
                    repo.get("c", AudibleRegion.US).shouldNotBeNull()
                }
            }
        }

        test("deleteExpired returns 0 when no rows have expired") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                val freshAt = now.plus(1.hours).toEpochMilliseconds()
                runTest {
                    repo.put("a", AudibleRegion.US, "x", freshAt)
                    val removed = repo.deleteExpired(beforeMs = now.toEpochMilliseconds())
                    removed shouldBe 0
                }
            }
        }
    })

// ─── Test clock helpers ───────────────────────────────────────────────────────

/** A mutable [Clock] for tests that need to advance time deterministically. */
@kotlin.time.ExperimentalTime
private class MutableClock(
    private var time: Instant,
) : Clock {
    override fun now(): Instant = time

    fun set(newTime: Instant) {
        time = newTime
    }
}
