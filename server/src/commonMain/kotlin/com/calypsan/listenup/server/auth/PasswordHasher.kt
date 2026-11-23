package com.calypsan.listenup.server.auth

/**
 * Argon2id password hashing. Output is the standard PHC string format
 * (`$argon2id$v=19$m=65536,t=3,p=4$...`). Parameters are pinned identically across
 * platforms so a hash produced on one verifies on the other.
 *
 * JVM actual: password4j. Native actual: libargon2 cinterop.
 *
 * An empty password is never a valid one: [verify] rejects it outright on both actuals, so the two
 * agree even for a hash of the empty string (password4j would not verify one, libargon2 would).
 * [hash] accepts it and does the full work, so a timing-equalising hash costs the same for any input.
 */
expect class PasswordHasher() {
    suspend fun hash(plaintext: CharSequence): String

    suspend fun verify(
        plaintext: CharSequence,
        encoded: String,
    ): Boolean
}
