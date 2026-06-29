package com.calypsan.listenup.server.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Native parity proof for [PasswordHasher] — the libargon2-cinterop actual. The cross-impl vector test
 * (below) is the real anti-drift guard: it pins a PHC string PRODUCED BY THE JVM actual (password4j) and
 * asserts the NATIVE [PasswordHasher.verify] accepts it. If the libargon2 marshalling or PHC parsing ever
 * drifts from password4j's encoding, native verify returns false and existing users can no longer log in
 * on the native server — this test fails first.
 */
class PasswordHasherNativeTest {
    @Test
    fun nativeHashVerifyRoundTrips(): Unit =
        runBlocking {
            val hasher = PasswordHasher()
            val phc = hasher.hash("native-round-trip-password")
            phc shouldStartWith "$" + "argon2id"
            hasher.verify("native-round-trip-password", phc) shouldBe true
            hasher.verify("wrong-password!!", phc) shouldBe false
        }

    @Test
    fun nativeVerifyAcceptsAJvmProducedHash(): Unit =
        runBlocking {
            // VECTOR PROVENANCE: produced by the JVM actual (password4j) from `plaintext` below, via the
            // plan's Step 3 generation procedure. Do NOT hand-edit. If the Argon2 parameters ever change,
            // regenerate it from the JVM impl — a hand-written value defeats the anti-drift purpose.
            val plaintext = "listenup-native-parity-vector"
            val jvmProducedHash =
                "\$argon2id\$v=19\$m=65536,t=3,p=4\$9j3C+x1TtkZNN1++COnYDQ\$x2M9twfkhkgdGh8DmhzTs4P2tKulagocBE6BDiontW4"
            PasswordHasher().verify(plaintext, jvmProducedHash) shouldBe true
        }
}
