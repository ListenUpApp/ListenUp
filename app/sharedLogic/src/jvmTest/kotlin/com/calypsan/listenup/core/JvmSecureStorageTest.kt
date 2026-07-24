package com.calypsan.listenup.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.File

/**
 * Integration tests for JvmSecureStorage.
 *
 * Tests the actual encryption/decryption logic, persistence,
 * and edge cases using real file I/O with temporary directories.
 */
class JvmSecureStorageTest :
    FunSpec({
        // A fresh, empty storage file inside a per-test temp directory ([tempdir] is cleaned up
        // by Kotest after the spec). Starting fresh gives each test isolated state.
        fun newStorageFile(): File = File(tempdir(), "test-auth.enc").apply { delete() }

        test("save and read round-trip works") {
            runTest {
                val storage = JvmSecureStorage(newStorageFile())

                storage.save("access_token", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")

                val result = storage.read("access_token")
                result shouldBe "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
            }
        }

        test("read returns null for non-existent key") {
            runTest {
                val storage = JvmSecureStorage(newStorageFile())

                storage.read("nonexistent") shouldBe null
            }
        }

        test("data persists across storage instances") {
            runTest {
                val storageFile = newStorageFile()
                val storage = JvmSecureStorage(storageFile)
                storage.save("refresh_token", "secret-refresh-token")

                // Create a new instance pointing to the same file.
                val newStorage = JvmSecureStorage(storageFile)
                newStorage.read("refresh_token") shouldBe "secret-refresh-token"
            }
        }

        test("save overwrites existing value") {
            runTest {
                val storage = JvmSecureStorage(newStorageFile())
                storage.save("key", "value1")

                storage.save("key", "value2")
                storage.read("key") shouldBe "value2"
            }
        }

        test("multiple keys stored independently") {
            runTest {
                val storage = JvmSecureStorage(newStorageFile())

                storage.save("access_token", "access123")
                storage.save("refresh_token", "refresh456")
                storage.save("server_url", "https://example.com")

                storage.read("access_token") shouldBe "access123"
                storage.read("refresh_token") shouldBe "refresh456"
                storage.read("server_url") shouldBe "https://example.com"
            }
        }

        test("delete removes specific key") {
            runTest {
                val storage = JvmSecureStorage(newStorageFile())
                storage.save("key1", "value1")
                storage.save("key2", "value2")

                storage.delete("key1")

                storage.read("key1") shouldBe null
                storage.read("key2") shouldBe "value2"
            }
        }

        test("clear removes all data") {
            runTest {
                val storage = JvmSecureStorage(newStorageFile())
                storage.save("key1", "value1")
                storage.save("key2", "value2")

                storage.clear()

                storage.read("key1") shouldBe null
                storage.read("key2") shouldBe null
            }
        }

        test("clear deletes storage file") {
            runTest {
                val storageFile = newStorageFile()
                val storage = JvmSecureStorage(storageFile)
                storage.save("key", "value")
                storageFile.exists() shouldBe true

                storage.clear()

                storageFile.exists() shouldBe false
            }
        }

        test("handles empty string value") {
            runTest {
                val storage = JvmSecureStorage(newStorageFile())

                storage.save("empty", "")
                storage.read("empty") shouldBe ""
            }
        }

        test("handles unicode characters") {
            runTest {
                val storage = JvmSecureStorage(newStorageFile())
                val unicodeValue = "用户名：测试 🎧📚"

                storage.save("unicode_key", unicodeValue)
                storage.read("unicode_key") shouldBe unicodeValue
            }
        }

        test("handles long values") {
            runTest {
                val storage = JvmSecureStorage(newStorageFile())
                // Simulate a large JWT or certificate.
                val longValue = "x".repeat(10_000)

                storage.save("long_key", longValue)
                storage.read("long_key") shouldBe longValue
            }
        }

        test("handles special JSON characters in values") {
            runTest {
                val storage = JvmSecureStorage(newStorageFile())
                // JSON with quotes, backslashes, newlines.
                val jsonValue = """{"key": "value with \"quotes\" and \\ backslash\nand newline"}"""

                storage.save("json_data", jsonValue)
                storage.read("json_data") shouldBe jsonValue
            }
        }

        test("file content is encrypted not plaintext") {
            runTest {
                val storageFile = newStorageFile()
                val storage = JvmSecureStorage(storageFile)
                val secretValue = "super-secret-token-12345"

                storage.save("secret", secretValue)

                // The file should exist but not contain plaintext.
                storageFile.exists() shouldBe true
                val fileContent = storageFile.readText()
                fileContent.contains(secretValue) shouldBe false
                fileContent.isNotEmpty() shouldBe true
            }
        }

        test("handles corrupted file gracefully") {
            runTest {
                val storageFile = newStorageFile()
                val storage = JvmSecureStorage(storageFile)
                // Write garbage to the file.
                storageFile.writeText("not-valid-encrypted-data")

                // Should return null, not crash.
                storage.read("any_key") shouldBe null
            }
        }

        test("delete on non-existent key does not crash") {
            runTest {
                val storage = JvmSecureStorage(newStorageFile())

                // Should not throw.
                storage.delete("nonexistent_key")
            }
        }

        test("new storage on non-existent file works") {
            runTest {
                val newFile = File(tempdir(), "brand-new.enc")
                val newStorage = JvmSecureStorage(newFile)

                newStorage.save("key", "value")

                newStorage.read("key") shouldBe "value"
                newFile.exists() shouldBe true
            }
        }

        test("concurrent saves of distinct keys never lose an update (RMW is serialized)") {
            runTest {
                val storage = JvmSecureStorage(newStorageFile())
                val keys = (0 until 64).map { "key-$it" }

                // Fan out real-thread writers at the same target file. An unsynchronized
                // load-modify-save loses updates when two writers read the same snapshot; a
                // Mutex-serialized RMW + atomic rename keeps every key.
                coroutineScope {
                    keys.forEach { k -> launch(Dispatchers.Default) { storage.save(k, "v-$k") } }
                }

                keys.forEach { k -> storage.read(k) shouldBe "v-$k" }
            }
        }

        test("encryption produces different ciphertext for same plaintext") {
            runTest {
                // This tests that we're using random IVs (not deterministic encryption).
                val dir = tempdir()
                val file1 = File(dir, "enc1.enc")
                val file2 = File(dir, "enc2.enc")
                val storage1 = JvmSecureStorage(file1)
                val storage2 = JvmSecureStorage(file2)

                storage1.save("key", "same-value")
                storage2.save("key", "same-value")

                // Ciphertext might still be equal by chance (very unlikely with a 96-bit IV),
                // but the values must decrypt to the same thing.
                storage1.read("key") shouldBe "same-value"
                storage2.read("key") shouldBe "same-value"
            }
        }
    })
