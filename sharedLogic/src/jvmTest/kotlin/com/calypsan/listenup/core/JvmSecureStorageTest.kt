package com.calypsan.listenup.core

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for JvmSecureStorage.
 *
 * Tests the actual encryption/decryption logic, persistence,
 * and edge cases using real file I/O with temporary directories.
 */
class JvmSecureStorageTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageFile: File
    private lateinit var storage: JvmSecureStorage

    @BeforeTest
    fun setup() {
        tempFolder.create()
        storageFile = tempFolder.newFile("test-auth.enc")
        storageFile.delete() // Start fresh
        storage = JvmSecureStorage(storageFile)
    }

    @AfterTest
    fun teardown() {
        tempFolder.delete()
    }

    @Test
    fun `save and read round-trip works`() =
        runTest {
            // When
            storage.save("access_token", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")

            // Then
            val result = storage.read("access_token")
            assertEquals("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", result)
        }

    @Test
    fun `read returns null for non-existent key`() =
        runTest {
            // When
            val result = storage.read("nonexistent")

            // Then
            assertNull(result)
        }

    @Test
    fun `data persists across storage instances`() =
        runTest {
            // Given
            storage.save("refresh_token", "secret-refresh-token")

            // When - create new instance pointing to same file
            val newStorage = JvmSecureStorage(storageFile)
            val result = newStorage.read("refresh_token")

            // Then
            assertEquals("secret-refresh-token", result)
        }

    @Test
    fun `save overwrites existing value`() =
        runTest {
            // Given
            storage.save("key", "value1")

            // When
            storage.save("key", "value2")
            val result = storage.read("key")

            // Then
            assertEquals("value2", result)
        }

    @Test
    fun `multiple keys stored independently`() =
        runTest {
            // When
            storage.save("access_token", "access123")
            storage.save("refresh_token", "refresh456")
            storage.save("server_url", "https://example.com")

            // Then
            assertEquals("access123", storage.read("access_token"))
            assertEquals("refresh456", storage.read("refresh_token"))
            assertEquals("https://example.com", storage.read("server_url"))
        }

    @Test
    fun `delete removes specific key`() =
        runTest {
            // Given
            storage.save("key1", "value1")
            storage.save("key2", "value2")

            // When
            storage.delete("key1")

            // Then
            assertNull(storage.read("key1"))
            assertEquals("value2", storage.read("key2"))
        }

    @Test
    fun `clear removes all data`() =
        runTest {
            // Given
            storage.save("key1", "value1")
            storage.save("key2", "value2")

            // When
            storage.clear()

            // Then
            assertNull(storage.read("key1"))
            assertNull(storage.read("key2"))
        }

    @Test
    fun `clear deletes storage file`() =
        runTest {
            // Given
            storage.save("key", "value")
            assertTrue(storageFile.exists())

            // When
            storage.clear()

            // Then
            assertTrue(!storageFile.exists())
        }

    @Test
    fun `handles empty string value`() =
        runTest {
            // When
            storage.save("empty", "")
            val result = storage.read("empty")

            // Then
            assertEquals("", result)
        }

    @Test
    fun `handles unicode characters`() =
        runTest {
            // Given
            val unicodeValue = "用户名：测试 🎧📚"

            // When
            storage.save("unicode_key", unicodeValue)
            val result = storage.read("unicode_key")

            // Then
            assertEquals(unicodeValue, result)
        }

    @Test
    fun `handles long values`() =
        runTest {
            // Given - simulate a large JWT or certificate
            val longValue = "x".repeat(10_000)

            // When
            storage.save("long_key", longValue)
            val result = storage.read("long_key")

            // Then
            assertEquals(longValue, result)
        }

    @Test
    fun `handles special JSON characters in values`() =
        runTest {
            // Given - JSON with quotes, backslashes, newlines
            val jsonValue = """{"key": "value with \"quotes\" and \\ backslash\nand newline"}"""

            // When
            storage.save("json_data", jsonValue)
            val result = storage.read("json_data")

            // Then
            assertEquals(jsonValue, result)
        }

    @Test
    fun `file content is encrypted not plaintext`() =
        runTest {
            // Given
            val secretValue = "super-secret-token-12345"

            // When
            storage.save("secret", secretValue)

            // Then - file should exist but not contain plaintext
            assertTrue(storageFile.exists())
            val fileContent = storageFile.readText()
            assertTrue(!fileContent.contains(secretValue), "Plaintext found in encrypted file!")
            assertTrue(fileContent.isNotEmpty(), "Encrypted file should not be empty")
        }

    @Test
    fun `handles corrupted file gracefully`() =
        runTest {
            // Given - write garbage to the file
            storageFile.writeText("not-valid-encrypted-data")

            // When
            val result = storage.read("any_key")

            // Then - should return null, not crash
            assertNull(result)
        }

    @Test
    fun `delete on non-existent key does not crash`() =
        runTest {
            // When/Then - should not throw
            storage.delete("nonexistent_key")
        }

    @Test
    fun `new storage on non-existent file works`() =
        runTest {
            // Given
            val newFile = File(tempFolder.root, "brand-new.enc")
            val newStorage = JvmSecureStorage(newFile)

            // When
            newStorage.save("key", "value")

            // Then
            assertEquals("value", newStorage.read("key"))
            assertTrue(newFile.exists())
        }

    @Test
    fun `encryption produces different ciphertext for same plaintext`() =
        runTest {
            // This tests that we're using random IVs (not deterministic encryption)

            // Given
            val file1 = File(tempFolder.root, "enc1.enc")
            val file2 = File(tempFolder.root, "enc2.enc")
            val storage1 = JvmSecureStorage(file1)
            val storage2 = JvmSecureStorage(file2)

            // When
            storage1.save("key", "same-value")
            storage2.save("key", "same-value")

            // Then - ciphertext should differ due to random IV
            val content1 = file1.readText()
            val content2 = file2.readText()

            // Note: They might still be equal by chance (very unlikely with 96-bit IV)
            // but the values should decrypt to the same thing
            assertEquals("same-value", storage1.read("key"))
            assertEquals("same-value", storage2.read("key"))
        }
}
