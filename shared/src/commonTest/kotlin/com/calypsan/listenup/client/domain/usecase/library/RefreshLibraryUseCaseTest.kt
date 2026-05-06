package com.calypsan.listenup.client.domain.usecase.library

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.SyncRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for RefreshLibraryUseCase.
 *
 * Tests cover:
 * - Successful sync flow
 * - Sync error handling and user-friendly messages
 * - Library mismatch handling
 * - Reset for new library flow
 */
class RefreshLibraryUseCaseTest {
    // ========== Test Fixtures ==========

    private class TestFixture {
        val syncRepository: SyncRepository = mock()
        val syncStateFlow = MutableStateFlow<SyncState>(SyncState.Idle)

        init {
            every { syncRepository.syncState } returns syncStateFlow
        }

        fun build(): RefreshLibraryUseCase =
            RefreshLibraryUseCase(
                syncRepository = syncRepository,
            )
    }

    private fun createFixture(): TestFixture = TestFixture()

    // ========== Successful Sync Tests ==========

    @Test
    fun `successful sync returns success result`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.syncRepository.sync() } returns Success(Unit)
            val useCase = fixture.build()

            // When
            val result = useCase()

            // Then
            val success = assertIs<Success<RefreshLibraryResult>>(result)
            assertEquals("Library refreshed successfully", success.data.message)
        }

    @Test
    fun `sync updates state flow`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.syncRepository.sync() } returns Success(Unit)
            val useCase = fixture.build()

            // When
            fixture.syncStateFlow.value = SyncState.Syncing
            val result = useCase()
            fixture.syncStateFlow.value = SyncState.Success(timestamp = Timestamp.now())

            // Then
            val success = assertIs<Success<RefreshLibraryResult>>(result)
            // State flow accessible via useCase.syncState
            assertEquals(fixture.syncStateFlow.value, useCase.syncState.value)
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `sync failure returns failure with user-friendly message`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.syncRepository.sync() } returns
                Failure(Exception("Connection refused"))
            val useCase = fixture.build()

            // When
            val result = useCase()

            // Then
            val failure = assertIs<Failure>(result)
            assertTrue(failure.message.isNotEmpty())
        }

    // mapErrorMessage tests assert on the *exact* user-friendly text the named branch produces.
    // Loose substring matches would silently pass via the `else` fallback when the named branch
    // is broken (the prior shape of these tests had this exact bug — see code-review feedback
    // on Task 15a). The user-friendly text travels via the use case's `RefreshException` →
    // `suspendRunCatching` → `Failure(throwable)` → `ErrorMapper.map` → `InternalError(debugInfo
    // = "RefreshException: <user-friendly text>")`, so we read it from `debugInfo`.

    @Test
    fun `NetworkUnavailable maps to network user-friendly message`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.syncRepository.sync() } returns
                Failure(TransportError.NetworkUnavailable())
            val useCase = fixture.build()

            val result = useCase()

            val failure = assertIs<Failure>(result)
            val debugInfo = failure.error.debugInfo ?: ""
            assertTrue(
                debugInfo.contains("Unable to connect to server. Check your network connection."),
                "Expected mapErrorMessage's NetworkUnavailable branch; got debugInfo=\"$debugInfo\"",
            )
        }

    @Test
    fun `Timeout maps to not-responding user-friendly message`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.syncRepository.sync() } returns
                Failure(TransportError.Timeout())
            val useCase = fixture.build()

            val result = useCase()

            val failure = assertIs<Failure>(result)
            val debugInfo = failure.error.debugInfo ?: ""
            assertTrue(
                debugInfo.contains("Server is not responding. Please try again later."),
                "Expected mapErrorMessage's Timeout branch; got debugInfo=\"$debugInfo\"",
            )
        }

    @Test
    fun `AuthError maps to session-expired user-friendly message`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.syncRepository.sync() } returns
                Failure(AuthError.SessionExpired())
            val useCase = fixture.build()

            val result = useCase()

            val failure = assertIs<Failure>(result)
            val debugInfo = failure.error.debugInfo ?: ""
            assertTrue(
                debugInfo.contains("Session expired. Please log in again."),
                "Expected mapErrorMessage's AuthError branch; got debugInfo=\"$debugInfo\"",
            )
        }

    // ========== Reset for New Library Tests ==========

    @Test
    fun `resetForNewLibrary calls sync repository with library id`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.syncRepository.resetForNewLibrary(any()) } returns Success(Unit)
            val useCase = fixture.build()

            // When
            useCase.resetForNewLibrary("new-library-id")

            // Then
            verifySuspend {
                fixture.syncRepository.resetForNewLibrary("new-library-id")
            }
        }

    @Test
    fun `resetForNewLibrary success returns success result`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.syncRepository.resetForNewLibrary(any()) } returns Success(Unit)
            val useCase = fixture.build()

            // When
            val result = useCase.resetForNewLibrary("new-library-id")

            // Then
            val success = assertIs<Success<RefreshLibraryResult>>(result)
            assertEquals("Library synced with new server", success.data.message)
        }

    @Test
    fun `resetForNewLibrary failure returns failure`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.syncRepository.resetForNewLibrary(any()) } returns
                Failure(Exception("Reset failed"))
            val useCase = fixture.build()

            // When
            val result = useCase.resetForNewLibrary("new-library-id")

            // Then
            val failure = assertIs<Failure>(result)
            assertTrue(failure.message.isNotEmpty())
        }

    // ========== SyncState Access Tests ==========

    @Test
    fun `syncState exposes sync repository state flow`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            fixture.syncStateFlow.value = SyncState.Syncing

            // Then
            assertEquals(SyncState.Syncing, useCase.syncState.value)
        }

    @Test
    fun `syncState reflects success status after sync`() =
        runTest {
            // Given
            val fixture = createFixture()
            val timestamp = Timestamp.now()
            val useCase = fixture.build()

            // When
            fixture.syncStateFlow.value = SyncState.Success(timestamp = timestamp)

            // Then
            val state = assertIs<SyncState.Success>(useCase.syncState.value)
            assertEquals(timestamp, state.timestamp)
        }
}
