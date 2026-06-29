package com.calypsan.listenup.server.auth

import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libargon2.argon2id_hash_encoded
import libargon2.argon2id_verify

@OptIn(ExperimentalForeignApi::class)
actual class PasswordHasher actual constructor() {
    actual suspend fun hash(plaintext: CharSequence): String =
        withContext(Dispatchers.Default) {
            val pwd = plaintext.toString().encodeToByteArray()
            val salt = CryptographyRandom.nextBytes(SALT_BYTES)
            memScoped {
                val encodedLen = 256
                val out = allocArray<ByteVar>(encodedLen)
                val rc =
                    argon2id_hash_encoded(
                        ITERATIONS.convert(),
                        MEMORY_KIB.convert(),
                        PARALLELISM.convert(),
                        pwd.refTo(0),
                        pwd.size.convert(),
                        salt.refTo(0),
                        salt.size.convert(),
                        OUTPUT_LENGTH.convert(),
                        out,
                        encodedLen.convert(),
                    )
                check(rc == 0) { "argon2id_hash_encoded failed rc=$rc" }
                out.toKString()
            }
        }

    actual suspend fun verify(
        plaintext: CharSequence,
        encoded: String,
    ): Boolean =
        withContext(Dispatchers.Default) {
            val pwd = plaintext.toString().encodeToByteArray()
            // `encoded` is @CString in the generated binding (auto String→C string); pass directly.
            argon2id_verify(encoded, pwd.refTo(0), pwd.size.convert()) == 0
        }

    companion object {
        private const val MEMORY_KIB = 64 * 1024
        private const val ITERATIONS = 3
        private const val PARALLELISM = 4
        private const val OUTPUT_LENGTH = 32
        private const val SALT_BYTES = 16
    }
}
