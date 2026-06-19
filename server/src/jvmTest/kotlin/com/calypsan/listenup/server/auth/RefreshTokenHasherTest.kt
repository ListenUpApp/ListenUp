package com.calypsan.listenup.server.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class RefreshTokenHasherTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()

        test("rejects pepper shorter than 32 bytes") {
            shouldThrow<IllegalArgumentException> {
                RefreshTokenHasher("short".toByteArray())
            }
        }

        test("hash is deterministic for the same token + pepper") {
            val hasher = RefreshTokenHasher(pepper)
            hasher.hash("token") shouldBe hasher.hash("token")
        }

        test("hash output is 64 lowercase hex chars") {
            val hasher = RefreshTokenHasher(pepper)
            val out = hasher.hash("token")
            out.length shouldBe 64
            out.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
        }

        test("different peppers produce different hashes for the same token") {
            val a = RefreshTokenHasher("a".repeat(32).toByteArray())
            val b = RefreshTokenHasher("b".repeat(32).toByteArray())
            a.hash("token") shouldNotBe b.hash("token")
        }

        test("different tokens produce different hashes under the same pepper") {
            val hasher = RefreshTokenHasher(pepper)
            hasher.hash("token-a") shouldNotBe hasher.hash("token-b")
        }
    })
