package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.testing.MutableClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private fun details(id: Long) = HardcoverBookDetails(id, "About $id", emptyList(), emptyList(), emptyList(), emptyList())

/** What one match preview remembers, and for how long (#1542). */
class HardcoverMetadataCacheTest :
    FunSpec({
        test("a book's details are remembered for six hours") {
            val clock = MutableClock(Instant.parse("2026-10-01T12:00:00Z"))
            val cache = HardcoverMetadataCache(clock)
            cache.rememberDetails(details(1L))

            clock.instant += 5.hours
            cache.details(1L) shouldBe details(1L)
            clock.instant += 2.hours
            cache.details(1L).shouldBeNull()
        }

        test("which book a match is, or that it is none, is remembered for ten minutes") {
            val clock = MutableClock(Instant.parse("2026-10-01T12:00:00Z"))
            val cache = HardcoverMetadataCache(clock)
            cache.rememberResolution("b1|B01", HardcoverResolution.Book(7L))
            cache.rememberResolution("b2|B02", HardcoverResolution.NoMatch)

            clock.instant += 9.minutes
            cache.resolution("b1|B01") shouldBe HardcoverResolution.Book(7L)
            cache.resolution("b2|B02") shouldBe HardcoverResolution.NoMatch
            clock.instant += 2.minutes
            cache.resolution("b1|B01").shouldBeNull()
        }

        test("past its capacity the oldest entry goes first") {
            val cache = HardcoverMetadataCache(capacity = 2)
            cache.rememberDetails(details(1L))
            cache.rememberDetails(details(2L))
            cache.rememberDetails(details(3L))

            cache.details(1L).shouldBeNull()
            cache.details(3L) shouldBe details(3L)
        }
    })
