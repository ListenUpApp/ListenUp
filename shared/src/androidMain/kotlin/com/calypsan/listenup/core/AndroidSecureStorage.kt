package com.calypsan.listenup.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val logger = KotlinLogging.logger {}

/**
 * Modern Android implementation of SecureStorage using Android KeyStore directly.
 *
 * Features:
 * - Direct use of Android KeyStore API (no deprecated libraries)
 * - AES-256-GCM encryption for maximum security
 * - Hardware-backed keys on supported devices (Android 32+)
 * - StrongBox security module support when available
 * - All I/O operations on Dispatchers.IO
 *
 * @param context Android application context
 */
internal class AndroidSecureStorage(
    private val context: Context,
) : SecureStorage {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("listenup_secure_prefs", Context.MODE_PRIVATE)
    }

    private val keyAlias = "listenup_master_key"
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    private val secretKey: SecretKey
        get() = keyStore.getKey(keyAlias, null) as? SecretKey ?: generateKey()

    private fun generateKey(): SecretKey {
        val keyGenerator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore",
            )

        // Always try StrongBox (enhanced hardware security), fall back to regular if unavailable
        // Note: StrongBox available on Android P+ (API 28+), minSdk is 34
        val useStrongBox = true

        return try {
            val spec =
                KeyGenParameterSpec
                    .Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .apply {
                        if (useStrongBox) {
                            setIsStrongBoxBacked(true)
                        }
                    }.build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            // StrongBox not available or other error - retry without StrongBox
            if (useStrongBox) {
                val spec =
                    KeyGenParameterSpec
                        .Builder(
                            keyAlias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .build()

                keyGenerator.init(spec)
                keyGenerator.generateKey()
            } else {
                // Not a StrongBox issue, rethrow original exception
                throw e
            }
        }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Combine IV and ciphertext: [IV_LENGTH(1 byte)][IV][CIPHERTEXT]
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encrypted: String): String {
        val combined = Base64.decode(encrypted, Base64.NO_WRAP)

        val ivLength = combined[0].toInt()
        val iv = ByteArray(ivLength)
        val ciphertext = ByteArray(combined.size - 1 - ivLength)

        System.arraycopy(combined, 1, iv, 0, ivLength)
        System.arraycopy(combined, 1 + ivLength, ciphertext, 0, ciphertext.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    override suspend fun save(
        key: String,
        value: String,
    ) = withContext(Dispatchers.IO) {
        val encrypted = encrypt(value)
        prefs.edit().putString(key, encrypted).apply()
    }

    override suspend fun read(key: String): String? =
        withContext(Dispatchers.IO) {
            val encrypted = prefs.getString(key, null) ?: return@withContext null
            try {
                decrypt(encrypted)
            } catch (e: Exception) {
                // If decryption fails (corrupted data, key change), return null
                logger.warn(e) { "Decryption failed for key '$key' - data may be corrupted" }
                null
            }
        }

    override suspend fun delete(key: String) =
        withContext(Dispatchers.IO) {
            prefs.edit().remove(key).apply()
        }

    override suspend fun clear() =
        withContext(Dispatchers.IO) {
            prefs.edit().clear().apply()
        }
}

/**
 * Android factory function for creating SecureStorage instances.
 *
 * Note: This function is internal and should be called via Koin DI
 * which provides the Android application context.
 */
internal fun createAndroidSecureStorage(context: Context): SecureStorage = AndroidSecureStorage(context)
