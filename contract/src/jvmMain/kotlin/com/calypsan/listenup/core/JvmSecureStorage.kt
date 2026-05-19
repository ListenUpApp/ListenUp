package com.calypsan.listenup.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private val logger = KotlinLogging.logger {}

private const val ITERATION_COUNT = 100_000
private const val KEY_LENGTH = 256
private const val GCM_TAG_LENGTH = 128
private const val GCM_IV_LENGTH = 12
private const val SALT_LENGTH = 16

/**
 * JVM desktop implementation of SecureStorage using AES-256-GCM encryption.
 *
 * Features:
 * - AES-256-GCM encryption for data confidentiality and integrity
 * - PBKDF2 key derivation with machine-specific salt
 * - All data stored in a single encrypted JSON file
 * - Salt derived from hostname + username for machine-binding
 *
 * Storage location:
 * - Windows: %APPDATA%/ListenUp/auth.enc
 * - Linux: ~/.local/share/listenup/auth.enc
 *
 * @param storageFile The file where encrypted data is stored
 */
class JvmSecureStorage(
    private val storageFile: File,
) : SecureStorage {
    private val json = appJson
    private val secretKey: SecretKeySpec by lazy { deriveKey() }

    /**
     * Derive encryption key from machine-specific data.
     *
     * Uses PBKDF2-HMAC-SHA256 with a salt derived from:
     * - Hostname
     * - Username
     * - Static salt component
     *
     * This makes the encryption machine-specific, preventing token portability.
     */
    private fun deriveKey(): SecretKeySpec {
        val hostname =
            runCatching {
                java.net.InetAddress
                    .getLocalHost()
                    .hostName
            }.getOrDefault("unknown-host")

        val username = System.getProperty("user.name", "unknown-user")
        val saltSource = "$hostname:$username:listenup-v1"

        // Create deterministic salt from machine identity
        val saltBytes =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(saltSource.toByteArray(Charsets.UTF_8))
                .copyOf(SALT_LENGTH)

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec =
            PBEKeySpec(
                saltSource.toCharArray(),
                saltBytes,
                ITERATION_COUNT,
                KEY_LENGTH,
            )

        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypt data using AES-256-GCM.
     *
     * Format: [IV (12 bytes)][Ciphertext][Auth Tag (16 bytes)]
     */
    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Combine IV and ciphertext
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypt data using AES-256-GCM.
     */
    private fun decrypt(encrypted: String): String {
        val combined = Base64.getDecoder().decode(encrypted)

        val iv = ByteArray(GCM_IV_LENGTH)
        val ciphertext = ByteArray(combined.size - GCM_IV_LENGTH)

        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
        System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    /**
     * Load the current data store from disk.
     */
    private fun loadStore(): MutableMap<String, String> =
        if (storageFile.exists()) {
            try {
                val encrypted = storageFile.readText()
                val decrypted = decrypt(encrypted)
                json.decodeFromString<MutableMap<String, String>>(decrypted)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load secure storage, starting fresh" }
                mutableMapOf()
            }
        } else {
            mutableMapOf()
        }

    /**
     * Save the data store to disk.
     */
    private fun saveStore(store: Map<String, String>) {
        storageFile.parentFile?.mkdirs()
        val plaintext = json.encodeToString(store)
        val encrypted = encrypt(plaintext)
        storageFile.writeText(encrypted)
    }

    override suspend fun save(
        key: String,
        value: String,
    ) = withContext(Dispatchers.IO) {
        val store = loadStore()
        store[key] = value
        saveStore(store)
    }

    override suspend fun read(key: String): String? =
        withContext(Dispatchers.IO) {
            loadStore()[key]
        }

    override suspend fun delete(key: String) =
        withContext(Dispatchers.IO) {
            val store = loadStore()
            store.remove(key)
            saveStore(store)
        }

    override suspend fun clear() =
        withContext(Dispatchers.IO) {
            if (storageFile.exists()) {
                storageFile.delete()
            }
        }
}
