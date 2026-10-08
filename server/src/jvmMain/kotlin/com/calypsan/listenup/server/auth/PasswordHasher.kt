package com.calypsan.listenup.server.auth

import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2
import com.calypsan.listenup.server.util.cpuDispatcher
import kotlinx.coroutines.withContext

actual class PasswordHasher actual constructor() {
    actual suspend fun hash(plaintext: CharSequence): String =
        withContext(cpuDispatcher) {
            Password
                .hash(plaintext)
                .addRandomSalt(SALT_BYTES)
                .with(DEFAULT)
                .result
        }

    actual suspend fun verify(
        plaintext: CharSequence,
        encoded: String,
    ): Boolean =
        withContext(cpuDispatcher) {
            Password.check(plaintext, encoded).with(DEFAULT)
        }

    companion object {
        private const val MEMORY_KIB = 64 * 1024
        private const val ITERATIONS = 3
        private const val PARALLELISM = 4
        private const val OUTPUT_LENGTH = 32
        private const val SALT_BYTES = 16
        private const val ARGON_VERSION = 19

        private val DEFAULT: Argon2Function =
            Argon2Function.getInstance(
                MEMORY_KIB,
                ITERATIONS,
                PARALLELISM,
                OUTPUT_LENGTH,
                Argon2.ID,
                ARGON_VERSION,
            )
    }
}
