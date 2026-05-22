@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.audio

import com.calypsan.listenup.server.testing.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AudioUrlSignerTest :
    FunSpec({

        val key = "test-signing-key-with-enough-bytes".toByteArray()
        val now = Instant.fromEpochSeconds(1_750_000_000L)

        test("sign then verify succeeds for the same (userId, bookId, fileId)") {
            val signer = AudioUrlSigner(signingKey = key, clock = FixedClock(now))

            val query = signer.signedQuery("user1", "book1", "file1")
            val exp = query.substringAfter("exp=").substringBefore("&")
            val sig = query.substringAfter("sig=")

            signer.verify("user1", "book1", "file1", exp.toLong(), sig) shouldBe true
        }

        test("verify fails for a tampered sig") {
            val signer = AudioUrlSigner(signingKey = key, clock = FixedClock(now))

            val query = signer.signedQuery("user1", "book1", "file1")
            val exp = query.substringAfter("exp=").substringBefore("&")
            val sig = query.substringAfter("sig=")
            // Flip a nibble in the middle of the sig
            val tampered = sig.substring(0, 32) + "0" + sig.substring(33)

            signer.verify("user1", "book1", "file1", exp.toLong(), tampered) shouldBe false
        }

        test("verify fails for different (userId, bookId, fileId)") {
            val signer = AudioUrlSigner(signingKey = key, clock = FixedClock(now))

            val query = signer.signedQuery("user1", "book1", "file1")
            val exp = query.substringAfter("exp=").substringBefore("&")
            val sig = query.substringAfter("sig=")

            signer.verify("user2", "book1", "file1", exp.toLong(), sig) shouldBe false
            signer.verify("user1", "book2", "file1", exp.toLong(), sig) shouldBe false
            signer.verify("user1", "book1", "file2", exp.toLong(), sig) shouldBe false
        }

        test("verify fails when exp is in the past") {
            val pastClock = FixedClock(now - 1.seconds) // clock is 1s before now
            val signer = AudioUrlSigner(signingKey = key, ttl = 12.hours, clock = pastClock)

            val query = signer.signedQuery("user1", "book1", "file1")
            val exp = query.substringAfter("exp=").substringBefore("&")
            val sig = query.substringAfter("sig=")

            // Advance the clock past exp so the token is expired
            val futureClock = FixedClock(Instant.fromEpochSeconds(exp.toLong() + 1))
            val futureSigner = AudioUrlSigner(signingKey = key, clock = futureClock)

            futureSigner.verify("user1", "book1", "file1", exp.toLong(), sig) shouldBe false
        }

        test("verify fails for a malformed sig (not valid hex / wrong length)") {
            val signer = AudioUrlSigner(signingKey = key, clock = FixedClock(now))

            val exp = (now + 12.hours).epochSeconds

            signer.verify("user1", "book1", "file1", exp, "not-hex-at-all") shouldBe false
            signer.verify("user1", "book1", "file1", exp, "deadbeef") shouldBe false // too short
            signer.verify("user1", "book1", "file1", exp, "") shouldBe false
        }
    })
