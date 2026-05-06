package com.calypsan.listenup.client.domain.usecase

import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.Instance
import com.calypsan.listenup.client.domain.model.InstanceId
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Tests for GetInstanceUseCase.
 *
 * Tests cover:
 * - Delegation to repository
 * - Success and failure propagation
 * - ForceRefresh parameter forwarding
 */
class GetInstanceUseCaseTest {
    // ========== Test Fixtures ==========

    private class TestFixture {
        val repository: InstanceRepository = mock()

        fun build(): GetInstanceUseCase = GetInstanceUseCase(repository = repository)
    }

    private fun createFixture(): TestFixture = TestFixture()

    // ========== Test Data Factories ==========

    private fun createInstance(
        id: String = "instance-1",
        name: String = "Test Server",
        version: String = "1.0.0",
    ): Instance =
        Instance(
            id = InstanceId(id),
            name = name,
            version = version,
            localUrl = "http://localhost:8080",
            remoteUrl = null,
            setupRequired = false,
            createdAt = Instant.fromEpochMilliseconds(1704067200000L),
            updatedAt = Instant.fromEpochMilliseconds(1704067200000L),
        )

    // ========== Success Tests ==========

    @Test
    fun `invoke returns success from repository`() =
        runTest {
            // Given
            val fixture = createFixture()
            val instance = createInstance(name = "My Server")
            everySuspend { fixture.repository.getInstance(false) } returns Success(instance)
            val useCase = fixture.build()

            // When
            val result = useCase()

            // Then
            val success = assertIs<Success<Instance>>(result)
            assertEquals("My Server", success.data.name)
        }

    @Test
    fun `invoke returns failure from repository`() =
        runTest {
            // Given
            val fixture = createFixture()
            // Body-level message convention: pass a typed AppError so the
            // user-facing message survives delegation.
            everySuspend { fixture.repository.getInstance(false) } returns
                Failure(com.calypsan.listenup.api.error.ValidationError(message = "Network error"))
            val useCase = fixture.build()

            // When
            val result = useCase()

            // Then
            val failure = assertIs<Failure>(result)
            assertEquals("Network error", failure.message)
        }

    // ========== ForceRefresh Parameter Tests ==========

    @Test
    fun `invoke with forceRefresh true passes parameter to repository`() =
        runTest {
            // Given
            val fixture = createFixture()
            val instance = createInstance()
            everySuspend { fixture.repository.getInstance(true) } returns Success(instance)
            val useCase = fixture.build()

            // When
            useCase(forceRefresh = true)

            // Then
            verifySuspend { fixture.repository.getInstance(true) }
        }

    @Test
    fun `invoke with forceRefresh false passes parameter to repository`() =
        runTest {
            // Given
            val fixture = createFixture()
            val instance = createInstance()
            everySuspend { fixture.repository.getInstance(false) } returns Success(instance)
            val useCase = fixture.build()

            // When
            useCase(forceRefresh = false)

            // Then
            verifySuspend { fixture.repository.getInstance(false) }
        }

    @Test
    fun `invoke defaults to forceRefresh false`() =
        runTest {
            // Given
            val fixture = createFixture()
            val instance = createInstance()
            everySuspend { fixture.repository.getInstance(false) } returns Success(instance)
            val useCase = fixture.build()

            // When
            useCase()

            // Then
            verifySuspend { fixture.repository.getInstance(false) }
        }

    // ========== Instance Properties Tests ==========

    @Test
    fun `invoke returns complete instance data`() =
        runTest {
            // Given
            val fixture = createFixture()
            val instance =
                createInstance(
                    id = "test-123",
                    name = "Production Server",
                    version = "2.5.0",
                )
            everySuspend { fixture.repository.getInstance(false) } returns Success(instance)
            val useCase = fixture.build()

            // When
            val result = useCase()

            // Then
            val success = assertIs<Success<Instance>>(result)
            assertEquals("test-123", success.data.id.value)
            assertEquals("Production Server", success.data.name)
            assertEquals("2.5.0", success.data.version)
        }
}
