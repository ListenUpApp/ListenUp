package com.calypsan.listenup.client.domain.usecase.library

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

/**
 * Tests for RefreshLibraryUseCase.
 *
 * Tests cover:
 * - Successful sync flow
 * - Failure propagation (the use case returns the repository's [AppError] unmodified;
 *   user-message translation lives in the presentation layer)
 * - Reset for new library flow
 * - SyncState exposure
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
    fun `sync failure propagates repository AppError unmodified`() =
        runTest {
            // The use case is a pure pass-through on failure — translation to
            // user-facing copy happens in the presentation layer, not here.
            val fixture = createFixture()
            val repoFailure = Failure(Exception("Connection refused"))
            everySuspend { fixture.syncRepository.sync() } returns repoFailure
            val useCase = fixture.build()

            val result = useCase()

            val failure = assertIs<Failure>(result)
            assertEquals(repoFailure.error, failure.error)
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
    fun `resetForNewLibrary failure propagates repository AppError unmodified`() =
        runTest {
            val fixture = createFixture()
            val repoFailure = Failure(Exception("Reset failed"))
            everySuspend { fixture.syncRepository.resetForNewLibrary(any()) } returns repoFailure
            val useCase = fixture.build()

            val result = useCase.resetForNewLibrary("new-library-id")

            val failure = assertIs<Failure>(result)
            assertEquals(repoFailure.error, failure.error)
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
