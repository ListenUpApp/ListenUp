package com.calypsan.listenup.server.auth

import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Argon2id password hashing wrapper. Pins parameters in one place; exposes
 * suspend functions that wrap the blocking, CPU-bound hash on Dispatchers.Default.
 *
 * Encoded output is the standard PHC string format
 * (`$argon2id$v=19$m=65536,t=3,p=4$...`).
 */
class PasswordHasher(
    private val argon2: Argon2Function = DEFAULT,
) {
    suspend fun hash(plaintext: CharSequence): String =
        withContext(Dispatchers.Default) {
            Password
                .hash(plaintext)
                .addRandomSalt(SALT_BYTES)
                .with(argon2)
                .result
        }

    suspend fun verify(
        plaintext: CharSequence,
        encoded: String,
    ): Boolean =
        withContext(Dispatchers.Default) {
            Password.check(plaintext, encoded).with(argon2)
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
