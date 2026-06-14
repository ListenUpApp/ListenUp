package com.calypsan.listenup.client.domain.usecase

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.Instance
import com.calypsan.listenup.client.domain.model.InstanceId
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import com.calypsan.listenup.core.Timestamp

/**
 * Tests for GetInstanceUseCase.
 *
 * Tests cover:
 * - Delegation to repository
 * - Success and failure propagation
 * - ForceRefresh parameter forwarding
 */
class GetInstanceUseCaseTest :
    FunSpec({
        // ========== Test Fixtures ==========

        class TestFixture {
            val repository: InstanceRepository = mock()

            fun build(): GetInstanceUseCase = GetInstanceUseCase(repository = repository)
        }

        fun createFixture(): TestFixture = TestFixture()

        // ========== Test Data Factories ==========

        fun createInstance(
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
                createdAt = Timestamp(1704067200000L),
                updatedAt = Timestamp(1704067200000L),
            )

        // ========== Success Tests ==========

        test("invoke returns success from repository") {
            runTest {
                // Given
                val fixture = createFixture()
                val instance = createInstance(name = "My Server")
                everySuspend { fixture.repository.getInstance(false) } returns AppResult.Success(instance)
                val useCase = fixture.build()

                // When
                val result = useCase()

                // Then
                val success = result.shouldBeInstanceOf<AppResult.Success<Instance>>()
                success.data.name shouldBe "My Server"
            }
        }

        test("invoke returns failure from repository") {
            runTest {
                // Given
                val fixture = createFixture()
                // Body-level message convention: pass a typed AppError so the
                // user-facing message survives delegation.
                everySuspend { fixture.repository.getInstance(false) } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Network error"),
                    )
                val useCase = fixture.build()

                // When
                val result = useCase()

                // Then
                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.message shouldBe "Network error"
            }
        }

        // ========== ForceRefresh Parameter Tests ==========

        test("invoke with forceRefresh true passes parameter to repository") {
            runTest {
                // Given
                val fixture = createFixture()
                val instance = createInstance()
                everySuspend { fixture.repository.getInstance(true) } returns AppResult.Success(instance)
                val useCase = fixture.build()

                // When
                useCase(forceRefresh = true)

                // Then
                verifySuspend { fixture.repository.getInstance(true) }
            }
        }

        test("invoke with forceRefresh false passes parameter to repository") {
            runTest {
                // Given
                val fixture = createFixture()
                val instance = createInstance()
                everySuspend { fixture.repository.getInstance(false) } returns AppResult.Success(instance)
                val useCase = fixture.build()

                // When
                useCase(forceRefresh = false)

                // Then
                verifySuspend { fixture.repository.getInstance(false) }
            }
        }

        test("invoke defaults to forceRefresh false") {
            runTest {
                // Given
                val fixture = createFixture()
                val instance = createInstance()
                everySuspend { fixture.repository.getInstance(false) } returns AppResult.Success(instance)
                val useCase = fixture.build()

                // When
                useCase()

                // Then
                verifySuspend { fixture.repository.getInstance(false) }
            }
        }

        // ========== Instance Properties Tests ==========

        test("invoke returns complete instance data") {
            runTest {
                // Given
                val fixture = createFixture()
                val instance =
                    createInstance(
                        id = "test-123",
                        name = "Production Server",
                        version = "2.5.0",
                    )
                everySuspend { fixture.repository.getInstance(false) } returns AppResult.Success(instance)
                val useCase = fixture.build()

                // When
                val result = useCase()

                // Then
                val success = result.shouldBeInstanceOf<AppResult.Success<Instance>>()
                success.data.id.value shouldBe "test-123"
                success.data.name shouldBe "Production Server"
                success.data.version shouldBe "2.5.0"
            }
        }
    })
