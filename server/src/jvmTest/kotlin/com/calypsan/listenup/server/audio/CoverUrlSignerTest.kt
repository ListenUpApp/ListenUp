@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.audio

import com.calypsan.listenup.server.testing.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class CoverUrlSignerTest :
    FunSpec({
        val key = CoverUrlSigner.deriveSigningKey("test-jwt-secret")

        fun parse(query: String): Map<String, String> =
            query.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }

        test("a freshly signed query verifies") {
            val signer = CoverUrlSigner(key, ttl = 12.hours, clock = FixedClock(Instant.fromEpochSeconds(1000)))
            val params = parse(signer.signedQuery(userId = "u1", bookId = "b1"))
            signer.verify("u1", "b1", params.getValue("exp").toLong(), params.getValue("sig")) shouldBe true
        }

        test("a different book does not verify against another book's signature") {
            val signer = CoverUrlSigner(key, clock = FixedClock(Instant.fromEpochSeconds(1000)))
            val params = parse(signer.signedQuery("u1", "b1"))
            signer.verify("u1", "b2", params.getValue("exp").toLong(), params.getValue("sig")) shouldBe false
        }

        test("an expired signature does not verify") {
            val signer = CoverUrlSigner(key, ttl = 12.hours, clock = FixedClock(Instant.fromEpochSeconds(1000)))
            val params = parse(signer.signedQuery("u1", "b1"))
            val later = CoverUrlSigner(key, clock = FixedClock(Instant.fromEpochSeconds(1000 + 13 * 3600)))
            later.verify("u1", "b1", params.getValue("exp").toLong(), params.getValue("sig")) shouldBe false
        }

        test("a malformed signature does not verify") {
            val signer = CoverUrlSigner(key, clock = FixedClock(Instant.fromEpochSeconds(1000)))
            signer.verify("u1", "b1", exp = 999_999_999, sig = "not-hex") shouldBe false
        }
    })
