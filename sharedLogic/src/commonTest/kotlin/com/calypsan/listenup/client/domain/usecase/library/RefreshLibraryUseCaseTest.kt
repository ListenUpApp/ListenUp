package com.calypsan.listenup.client.domain.usecase.library

import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.SyncRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

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
class RefreshLibraryUseCaseTest :
    FunSpec({

        // ========== Test Fixtures ==========

        class TestFixture {
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

        fun createFixture(): TestFixture = TestFixture()

        // ========== Successful Sync Tests ==========

        test("successful sync returns success result") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.syncRepository.sync() } returns Success(Unit)
                val useCase = fixture.build()

                // When
                val result = useCase()

                // Then
                val success = result.shouldBeInstanceOf<Success<RefreshLibraryResult>>()
                success.data.message shouldBe "Library refreshed successfully"
            }
        }

        test("sync updates state flow") {
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
                result.shouldBeInstanceOf<Success<RefreshLibraryResult>>()
                // State flow accessible via useCase.syncState
                useCase.syncState.value shouldBe fixture.syncStateFlow.value
            }
        }

        // ========== Error Handling Tests ==========

        test("sync failure propagates repository AppError unmodified") {
            runTest {
                // The use case is a pure pass-through on failure — translation to
                // user-facing copy happens in the presentation layer, not here.
                val fixture = createFixture()
                val repoFailure = Failure(Exception("Connection refused"))
                everySuspend { fixture.syncRepository.sync() } returns repoFailure
                val useCase = fixture.build()

                val result = useCase()

                val failure = result.shouldBeInstanceOf<Failure>()
                failure.error shouldBe repoFailure.error
            }
        }

        // ========== Reset for New Library Tests ==========

        test("resetForNewLibrary calls sync repository with library id") {
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
        }

        test("resetForNewLibrary success returns success result") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.syncRepository.resetForNewLibrary(any()) } returns Success(Unit)
                val useCase = fixture.build()

                // When
                val result = useCase.resetForNewLibrary("new-library-id")

                // Then
                val success = result.shouldBeInstanceOf<Success<RefreshLibraryResult>>()
                success.data.message shouldBe "Library synced with new server"
            }
        }

        test("resetForNewLibrary failure propagates repository AppError unmodified") {
            runTest {
                val fixture = createFixture()
                val repoFailure = Failure(Exception("Reset failed"))
                everySuspend { fixture.syncRepository.resetForNewLibrary(any()) } returns repoFailure
                val useCase = fixture.build()

                val result = useCase.resetForNewLibrary("new-library-id")

                val failure = result.shouldBeInstanceOf<Failure>()
                failure.error shouldBe repoFailure.error
            }
        }

        // ========== SyncState Access Tests ==========

        test("syncState exposes sync repository state flow") {
            runTest {
                // Given
                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                fixture.syncStateFlow.value = SyncState.Syncing

                // Then
                useCase.syncState.value shouldBe SyncState.Syncing
            }
        }

        test("syncState reflects success status after sync") {
            runTest {
                // Given
                val fixture = createFixture()
                val timestamp = Timestamp.now()
                val useCase = fixture.build()

                // When
                fixture.syncStateFlow.value = SyncState.Success(timestamp = timestamp)

                // Then
                val state = useCase.syncState.value.shouldBeInstanceOf<SyncState.Success>()
                state.timestamp shouldBe timestamp
            }
        }
    })
