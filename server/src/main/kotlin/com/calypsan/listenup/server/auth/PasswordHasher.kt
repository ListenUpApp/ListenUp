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
 * (`$argon2id$v=19$m=19456,t=2,p=1$...`).
 *
 * Parameters are OWASP's recommended Argon2id baseline (m=19MiB, t=2, p=1). This is a deliberate,
 * resource-aware choice for self-hosted deployment: the earlier m=64MiB/t=3/p=4 was ~3.4x more memory
 * per hash with no standard-security benefit, and that 64MiB transient is what forced the native
 * image's heap cap up (#647). The PHC string embeds the params, so hashes produced under the old
 * parameters still verify — only new hashes use the lighter cost.
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
            // Verify against the parameters embedded in the hash itself, NOT the current [argon2]
            // default. password4j's `.with(function)` would otherwise force the default's params and
            // reject any hash produced under different ones — so a parameter change (e.g. the OWASP
            // 64MiB→19MiB move) would lock out every existing user. getInstanceFromHash reconstructs
            // the right function per hash, so old and new hashes both verify. (#647)
            Password.check(plaintext, encoded).with(Argon2Function.getInstanceFromHash(encoded))
        }

    companion object {
        // OWASP-recommended Argon2id baseline (m=19MiB, t=2, p=1) — see class KDoc.
        private const val MEMORY_KIB = 19 * 1024
        private const val ITERATIONS = 2
        private const val PARALLELISM = 1
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
