package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.RegistrationResult
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for RegisterUseCase.
 *
 * Tests cover:
 * - Email validation (format, length)
 * - Password validation (minimum 8 characters)
 * - First/last name validation (non-blank)
 * - Successful registration flow with pending state persistence
 * - Error propagation from repository
 */
class RegisterUseCaseTest {
    // ========== Test Fixtures ==========

    private class TestFixture {
        val authRepository: AuthRepository = mock()
        val authSession: AuthSession = mock()

        fun build(): RegisterUseCase =
            RegisterUseCase(
                authRepository = authRepository,
                authSession = authSession,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()

        // Default stubs for successful operations
        everySuspend { fixture.authSession.savePendingRegistration(any(), any()) } returns Unit

        return fixture
    }

    // ========== Test Data Factories ==========

    private fun createRegistrationResult(
        userId: String = "user-1",
        message: String = "Registration successful. Awaiting admin approval.",
    ): RegistrationResult =
        RegistrationResult(
            userId = userId,
            message = message,
        )

    // ========== Email Validation Tests ==========

    @Test
    fun `register rejects email without at symbol`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            val result =
                useCase(
                    email = "invalid.email",
                    password = "password123",
                    firstName = "John",
                    lastName = "Doe",
                )

            // Then
            val failure = assertIs<Failure>(result)
            assertTrue(failure.message.contains("email", ignoreCase = true))
            assertIs<com.calypsan.listenup.client.core.error.DataError>(failure.error)
        }

    @Test
    fun `register rejects empty email`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            val result =
                useCase(
                    email = "",
                    password = "password123",
                    firstName = "John",
                    lastName = "Doe",
                )

            // Then
            val failure = assertIs<Failure>(result)
            assertTrue(failure.message.contains("email", ignoreCase = true))
        }

    // ========== Password Validation Tests ==========

    @Test
    fun `register rejects password shorter than 8 characters`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            val result =
                useCase(
                    email = "user@example.com",
                    password = "1234567",
                    firstName = "John",
                    lastName = "Doe",
                )

            // Then
            val failure = assertIs<Failure>(result)
            assertTrue(failure.message.contains("8", ignoreCase = true))
            assertIs<com.calypsan.listenup.client.core.error.DataError>(failure.error)
        }

    @Test
    fun `register accepts password with exactly 8 characters`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authRepository.register(any(), any(), any(), any()) } returns createRegistrationResult()
            val useCase = fixture.build()

            // When
            val result =
                useCase(
                    email = "user@example.com",
                    password = "12345678",
                    firstName = "John",
                    lastName = "Doe",
                )

            // Then
            checkIs<Success<*>>(result)
        }

    // ========== Name Validation Tests ==========

    @Test
    fun `register rejects blank first name`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            val result =
                useCase(
                    email = "user@example.com",
                    password = "password123",
                    firstName = "   ",
                    lastName = "Doe",
                )

            // Then
            val failure = assertIs<Failure>(result)
            assertTrue(failure.message.contains("first name", ignoreCase = true))
        }

    @Test
    fun `register rejects blank last name`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            val result =
                useCase(
                    email = "user@example.com",
                    password = "password123",
                    firstName = "John",
                    lastName = "",
                )

            // Then
            val failure = assertIs<Failure>(result)
            assertTrue(failure.message.contains("last name", ignoreCase = true))
        }

    // ========== Successful Registration Flow Tests ==========

    @Test
    fun `register saves pending registration on success`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authRepository.register(any(), any(), any(), any()) } returns
                createRegistrationResult(
                    userId = "user-42",
                )
            val useCase = fixture.build()

            // When
            useCase(
                email = "user@example.com",
                password = "password123",
                firstName = "John",
                lastName = "Doe",
            )

            // Then
            verifySuspend {
                fixture.authSession.savePendingRegistration(
                    userId = "user-42",
                    email = "user@example.com",
                )
            }
        }

    @Test
    fun `register returns registration result on success`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authRepository.register(any(), any(), any(), any()) } returns
                createRegistrationResult(
                    userId = "user-1",
                    message = "Success!",
                )
            val useCase = fixture.build()

            // When
            val result =
                useCase(
                    email = "user@example.com",
                    password = "password123",
                    firstName = "John",
                    lastName = "Doe",
                )

            // Then
            val success = assertIs<Success<RegistrationResult>>(result)
            assertEquals("user-1", success.data.userId)
            assertEquals("Success!", success.data.message)
        }

    @Test
    fun `register trims names before repository call`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authRepository.register(any(), any(), any(), any()) } returns createRegistrationResult()
            val useCase = fixture.build()

            // When
            useCase(
                email = "user@example.com",
                password = "password123",
                firstName = "  John  ",
                lastName = "  Doe  ",
            )

            // Then
            verifySuspend {
                fixture.authRepository.register(
                    email = "user@example.com",
                    password = "password123",
                    firstName = "John",
                    lastName = "Doe",
                )
            }
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `register returns failure when repository throws exception`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authRepository.register(any(), any(), any(), any()) } throws Exception("Registration disabled")
            val useCase = fixture.build()

            // When
            val result =
                useCase(
                    email = "user@example.com",
                    password = "password123",
                    firstName = "John",
                    lastName = "Doe",
                )

            // Then
            val failure = assertIs<Failure>(result)
            assertEquals("Registration disabled", failure.message)
        }
}
