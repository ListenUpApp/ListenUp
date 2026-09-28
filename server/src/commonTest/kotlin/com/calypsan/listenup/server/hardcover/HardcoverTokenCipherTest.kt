package com.calypsan.listenup.server.hardcover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.io.encoding.Base64

/**
 * Hardcover tokens are stored AES-256-GCM encrypted under a key derived from the JWT secret, so a
 * leaked database or backup alone doesn't expose anyone's account. A different secret (a restore onto
 * another server) or any tampering must fail CLOSED — null, never garbage or a throw.
 */
class HardcoverTokenCipherTest :
    FunSpec({
        val cipher = HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("jwt-secret-one-at-least-32-bytes-long"))

        test("round-trips a token") {
            cipher.decrypt(cipher.encrypt("hc_rt_abc123")) shouldBe "hc_rt_abc123"
        }

        test("the same token encrypts differently each time (fresh nonce)") {
            cipher.encrypt("hc_at_x") shouldNotBe cipher.encrypt("hc_at_x")
        }

        test("a different secret cannot decrypt") {
            val other = HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("jwt-secret-two-at-least-32-bytes-long"))
            other.decrypt(cipher.encrypt("hc_rt_abc123")).shouldBeNull()
        }

        test("tampered ciphertext fails closed") {
            val sealed = cipher.encrypt("hc_rt_abc123")
            val flipped = sealed.dropLast(2) + if (sealed.endsWith("AA")) "BB" else "AA"
            cipher.decrypt(flipped).shouldBeNull()
        }

        test("garbage fails closed") {
            cipher.decrypt("not base64 at all!").shouldBeNull()
        }

        test("Base64 too short to hold a nonce and a tag fails closed") {
            cipher.decrypt(Base64.encode(ByteArray(20))).shouldBeNull()
        }
    })
