package com.calypsan.listenup.server.matching

import com.calypsan.listenup.server.metadata.spi.FindAnswer
import com.calypsan.listenup.server.metadata.spi.FindLookup
import com.calypsan.listenup.server.metadata.spi.FindStep
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private class SteppingClock(
    var now: Instant = Instant.parse("2026-10-05T12:00:00Z"),
) : Clock {
    override fun now(): Instant = now
}

private fun key(
    text: String,
    region: String? = "us",
) = FindCache.Key(MetadataProviderId.AUDIBLE, FindLookup("b1", true, emptyList(), null, null, text, "T", null), region)

private val ANSWER = FindAnswer(emptyList(), setOf(FindStep.TEXT))

class FindCacheTest :
    FunSpec({
        test("an answer comes back until its ten minutes are up") {
            val clock = SteppingClock()
            val cache = FindCache(clock)
            cache.put(key("a"), ANSWER)
            clock.now += 9.minutes
            cache.get(key("a")) shouldBe ANSWER
            clock.now += 2.minutes
            cache.get(key("a")) shouldBe null
        }

        test("another query or another store is another entry") {
            val cache = FindCache(SteppingClock())
            cache.put(key("a"), ANSWER)
            cache.get(key("b")) shouldBe null
            cache.get(key("a", region = "uk")) shouldBe null
        }

        test("past capacity, the oldest entry goes first") {
            val cache = FindCache(SteppingClock(), capacity = 2)
            cache.put(key("a"), ANSWER)
            cache.put(key("b"), ANSWER)
            cache.put(key("c"), ANSWER)
            cache.get(key("a")) shouldBe null
            cache.get(key("c")) shouldBe ANSWER
        }
    })
