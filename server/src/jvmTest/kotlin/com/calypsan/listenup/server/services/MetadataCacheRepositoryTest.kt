@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
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

private val AUDIBLE = MetadataProviderId.AUDIBLE
private val AUDNEXUS = MetadataProviderId.AUDNEXUS

class MetadataCacheRepositoryTest :
    FunSpec({
        val now = Instant.parse("2026-05-24T12:00:00Z")

        test("get returns null for an unknown key") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                runTest {
                    repo.get(AUDIBLE, "us", "missing").shouldBeNull()
                }
            }
        }

        test("put then get round-trips the payload") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                val expiresAt = now.plus(24.hours).toEpochMilliseconds()
                runTest {
                    repo.put(AUDIBLE, "us", "search:harry potter", """{"results": []}""", expiresAt)
                    val cached = repo.get(AUDIBLE, "us", "search:harry potter")
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
                    repo.put(AUDIBLE, "us", "key1", "{}", expiresAt)
                    // Advance clock past expiry
                    clock.set(now.plus(2.hours))
                    repo.get(AUDIBLE, "us", "key1").shouldBeNull()
                    // Row was deleted — re-put with the same key works without a unique-constraint error
                    val newExpiry = clock.now().plus(1.hours).toEpochMilliseconds()
                    repo.put(AUDIBLE, "us", "key1", """{"new": true}""", newExpiry)
                    repo.get(AUDIBLE, "us", "key1") shouldBe """{"new": true}"""
                }
            }
        }

        test("get is region-scoped — same logical key in different regions is independent") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                val expiresAt = now.plus(1.hours).toEpochMilliseconds()
                runTest {
                    repo.put(AUDIBLE, "us", "k", "us-data", expiresAt)
                    repo.put(AUDIBLE, "uk", "k", "uk-data", expiresAt)
                    repo.get(AUDIBLE, "us", "k") shouldBe "us-data"
                    repo.get(AUDIBLE, "uk", "k") shouldBe "uk-data"
                }
            }
        }

        test("get is provider-scoped — same key+region for different providers is independent") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                val expiresAt = now.plus(1.hours).toEpochMilliseconds()
                runTest {
                    repo.put(AUDIBLE, "us", "book:B01", "audible-shape", expiresAt)
                    repo.put(AUDNEXUS, "us", "book:B01", "audnexus-shape", expiresAt)
                    repo.get(AUDIBLE, "us", "book:B01") shouldBe "audible-shape"
                    repo.get(AUDNEXUS, "us", "book:B01") shouldBe "audnexus-shape"
                }
            }
        }

        test("put replaces an existing entry for the same provider+region+key") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                val expiresAt = now.plus(1.hours).toEpochMilliseconds()
                runTest {
                    repo.put(AUDIBLE, "us", "k", "original", expiresAt)
                    repo.put(AUDIBLE, "us", "k", "updated", expiresAt)
                    repo.get(AUDIBLE, "us", "k") shouldBe "updated"
                }
            }
        }

        test("deleteExpired removes only expired rows and returns the count") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                val expiredAt = now.toEpochMilliseconds() - 1_000L
                val freshAt = now.plus(1.hours).toEpochMilliseconds()
                runTest {
                    repo.put(AUDIBLE, "us", "a", "x", expiredAt)
                    repo.put(AUDIBLE, "us", "b", "y", expiredAt)
                    repo.put(AUDIBLE, "us", "c", "z", freshAt)
                    val removed = repo.deleteExpired(beforeMs = now.toEpochMilliseconds())
                    removed shouldBe 2
                    repo.get(AUDIBLE, "us", "a").shouldBeNull()
                    repo.get(AUDIBLE, "us", "b").shouldBeNull()
                    repo.get(AUDIBLE, "us", "c").shouldNotBeNull()
                }
            }
        }

        test("deleteExpired returns 0 when no rows have expired") {
            withSqlDatabase {
                val repo = MetadataCacheRepository(sql, clock = FixedClock(now))
                val freshAt = now.plus(1.hours).toEpochMilliseconds()
                runTest {
                    repo.put(AUDIBLE, "us", "a", "x", freshAt)
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
