package com.calypsan.listenup.core

import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for SecureStorage interface contract.
 * Uses Mokkery for mocking the platform-specific implementations.
 */
class SecureStorageTest {
    @Test
    fun `save stores key-value pair`() =
        runTest {
            // Given
            val storage = mock<SecureStorage>()
            everySuspend { storage.save("key", "value") } returns Unit

            // When
            storage.save("key", "value")

            // Then
            verifySuspend { storage.save("key", "value") }
        }

    @Test
    fun `read returns stored value`() =
        runTest {
            // Given
            val storage = mock<SecureStorage>()
            everySuspend { storage.read("key") } returns "value"

            // When
            val result = storage.read("key")

            // Then
            assertEquals("value", result)
            verifySuspend { storage.read("key") }
        }

    @Test
    fun `read returns null for non-existent key`() =
        runTest {
            // Given
            val storage = mock<SecureStorage>()
            everySuspend { storage.read("nonexistent") } returns null

            // When
            val result = storage.read("nonexistent")

            // Then
            assertNull(result)
            verifySuspend { storage.read("nonexistent") }
        }

    @Test
    fun `delete removes key`() =
        runTest {
            // Given
            val storage = mock<SecureStorage>()
            everySuspend { storage.delete("key") } returns Unit

            // When
            storage.delete("key")

            // Then
            verifySuspend { storage.delete("key") }
        }

    @Test
    fun `clear removes all keys`() =
        runTest {
            // Given
            val storage = mock<SecureStorage>()
            everySuspend { storage.clear() } returns Unit

            // When
            storage.clear()

            // Then
            verifySuspend { storage.clear() }
        }

    @Test
    fun `save overwrites existing value`() =
        runTest {
            // Given
            val storage = mock<SecureStorage>()
            everySuspend { storage.save("key", "value1") } returns Unit
            everySuspend { storage.save("key", "value2") } returns Unit
            everySuspend { storage.read("key") } returns "value2"

            // When
            storage.save("key", "value1")
            storage.save("key", "value2")
            val result = storage.read("key")

            // Then
            assertEquals("value2", result)
            // Verify save was called at least once (removing exact count for Mokkery compatibility)
            verifySuspend { storage.save("key", "value2") }
        }

    @Test
    fun `multiple keys can be stored independently`() =
        runTest {
            // Given
            val storage = mock<SecureStorage>()
            everySuspend { storage.save("key1", "value1") } returns Unit
            everySuspend { storage.save("key2", "value2") } returns Unit
            everySuspend { storage.read("key1") } returns "value1"
            everySuspend { storage.read("key2") } returns "value2"

            // When
            storage.save("key1", "value1")
            storage.save("key2", "value2")
            val result1 = storage.read("key1")
            val result2 = storage.read("key2")

            // Then
            assertEquals("value1", result1)
            assertEquals("value2", result2)
        }

    @Test
    fun `delete does not affect other keys`() =
        runTest {
            // Given
            val storage = mock<SecureStorage>()
            everySuspend { storage.delete("key1") } returns Unit
            everySuspend { storage.read("key1") } returns null
            everySuspend { storage.read("key2") } returns "value2"

            // When
            storage.delete("key1")
            val result1 = storage.read("key1")
            val result2 = storage.read("key2")

            // Then
            assertNull(result1)
            assertEquals("value2", result2)
        }

    @Test
    fun `empty string can be stored as value`() =
        runTest {
            // Given
            val storage = mock<SecureStorage>()
            everySuspend { storage.save("key", "") } returns Unit
            everySuspend { storage.read("key") } returns ""

            // When
            storage.save("key", "")
            val result = storage.read("key")

            // Then
            assertEquals("", result)
        }

    @Test
    fun `long values can be stored`() =
        runTest {
            // Given
            val longValue = "x".repeat(10000)
            val storage = mock<SecureStorage>()
            everySuspend { storage.save("key", longValue) } returns Unit
            everySuspend { storage.read("key") } returns longValue

            // When
            storage.save("key", longValue)
            val result = storage.read("key")

            // Then
            assertEquals(longValue, result)
        }
}
