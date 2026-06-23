package com.calypsan.listenup.server.auth

/**
 * Argon2id password hashing. Output is the standard PHC string format
 * (`$argon2id$v=19$m=65536,t=3,p=4$...`). Parameters are pinned identically across
 * platforms so a hash produced on one verifies on the other.
 *
 * JVM actual: password4j. Native actual: libargon2 cinterop.
 */
expect class PasswordHasher() {
    suspend fun hash(plaintext: CharSequence): String

    suspend fun verify(plaintext: CharSequence, encoded: String): Boolean
}
