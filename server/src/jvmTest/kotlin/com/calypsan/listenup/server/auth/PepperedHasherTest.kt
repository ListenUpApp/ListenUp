package com.calypsan.listenup.server.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

class PepperedHasherTest :
    FunSpec({
        val pepper = ByteArray(32) { it.toByte() }

        test("hashing is deterministic — required for indexed lookup") {
            val hasher = PepperedHasher(pepper)
            hasher.hash("secret") shouldBe hasher.hash("secret")
        }

        test("output is 64 lowercase hex chars") {
            PepperedHasher(pepper).hash("secret") shouldMatch Regex("[0-9a-f]{64}")
        }

        test("a different pepper yields a different hash — a DB leak alone is not enough") {
            val other = ByteArray(32) { (it + 1).toByte() }
            PepperedHasher(pepper).hash("secret") shouldNotBe PepperedHasher(other).hash("secret")
        }

        test("a short pepper is rejected at construction") {
            shouldThrow<IllegalArgumentException> { PepperedHasher(ByteArray(31)) }
        }

        // Golden vectors computed independently with Python's hmac/hashlib, NOT from this
        // codebase — so they check the algorithm itself, not merely self-consistency.
        // RefreshTokenHasher hashes every refresh token in the system: if this output ever
        // shifts, every session on every deployed server dies at next refresh. That makes
        // these values a compatibility contract, not a convenience.
        test("output matches externally-computed HMAC-SHA-256 vectors") {
            val hasher = PepperedHasher(pepper)

            hasher.hash("tok") shouldBe
                "ebd522488f4f62e726c6ecfbe4f44bb2a9c7b4d17b1b8e923525449eb78acccb"
            hasher.hash("secret") shouldBe
                "723f228d66d0f98dd0b43d22396eda3938365077ed1737631b6ca62d2dca6b43"
        }

        test("RefreshTokenHasher produces the same golden vector — the wire contract is unchanged") {
            RefreshTokenHasher(pepper).hash("tok") shouldBe
                "ebd522488f4f62e726c6ecfbe4f44bb2a9c7b4d17b1b8e923525449eb78acccb"
        }
    })
