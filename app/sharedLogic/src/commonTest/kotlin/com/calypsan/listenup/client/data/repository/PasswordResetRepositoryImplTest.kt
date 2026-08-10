package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.PasswordResetStatus
import com.calypsan.listenup.api.dto.auth.PasswordResetStatusEvent
import com.calypsan.listenup.api.dto.auth.PasswordResetTicket
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.core.SecureStorage
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentiallyReturns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Drives [PasswordResetRepositoryImpl] through an [RpcChannel] wrapping a mocked
 * [AuthServicePublic] — no network. Pins the device-claim retention contract: the whole point of
 * the repository is that a user can close the app while waiting for an admin and come back to a
 * cold start, so [PasswordResetRepositoryImpl.CLAIM_KEY] and [PasswordResetRepositoryImpl.TICKET_KEY]
 * must genuinely round-trip through storage rather than living only in memory.
 */
class PasswordResetRepositoryImplTest :
    FunSpec({
        fun ticket(id: String = "ticket-1") = PasswordResetTicket(ticketId = id, expiresAt = 0L)

        fun serviceReturning(result: AppResult<PasswordResetTicket>): AuthServicePublic {
            val service = mock<AuthServicePublic>()
            everySuspend { service.requestPasswordReset(any(), any()) } returns result
            return service
        }

        fun repository(
            service: AuthServicePublic,
            storage: SecureStorage,
        ): PasswordResetRepositoryImpl = PasswordResetRepositoryImpl(RpcChannel.forTest(service), storage)

        test("requesting a reset persists the device claim so it survives process death") {
            runTest {
                val storage = InMemoryPasswordResetSecureStorage()
                val repo = repository(serviceReturning(AppResult.Success(ticket())), storage)

                val result = repo.requestReset("ada@example.com")

                result.shouldBeInstanceOf<AppResult.Success<PasswordResetTicket>>()
                storage.read(PasswordResetRepositoryImpl.CLAIM_KEY).shouldNotBeNull()
            }
        }

        test("the ticket id is persisted too — the claim alone cannot resume the flow") {
            runTest {
                val storage = InMemoryPasswordResetSecureStorage()
                val repo = repository(serviceReturning(AppResult.Success(ticket("ticket-42"))), storage)

                val result = repo.requestReset("ada@example.com")

                val success = result.shouldBeInstanceOf<AppResult.Success<PasswordResetTicket>>()
                storage.read(PasswordResetRepositoryImpl.TICKET_KEY) shouldBe success.data.ticketId
            }
        }

        test("a resumable request is recoverable after a cold start") {
            runTest {
                val storage = InMemoryPasswordResetSecureStorage()
                repository(serviceReturning(AppResult.Success(ticket())), storage).requestReset("ada@example.com")

                // A brand-new instance, as if the process had been killed and relaunched.
                val revived = repository(mock<AuthServicePublic>(), storage)

                revived.resumableTicketId().shouldNotBeNull()
            }
        }

        test("completing clears both stored values") {
            runTest {
                val storage = InMemoryPasswordResetSecureStorage()
                val service = serviceReturning(AppResult.Success(ticket("ticket-9")))
                everySuspend { service.completePasswordReset(any(), any(), any(), any()) } returns
                    AppResult.Success(Unit)
                val repo = repository(service, storage)
                val success = repo.requestReset("ada@example.com").shouldBeInstanceOf<AppResult.Success<PasswordResetTicket>>()

                repo.completeReset(success.data.ticketId, "ABCD-2345", "correct horse battery")

                storage.read(PasswordResetRepositoryImpl.CLAIM_KEY) shouldBe null
                storage.read(PasswordResetRepositoryImpl.TICKET_KEY) shouldBe null
            }
        }

        test("completing without a retained claim fails without calling the server") {
            runTest {
                val storage = InMemoryPasswordResetSecureStorage()
                val service = mock<AuthServicePublic>()
                val repo = repository(service, storage)

                val result = repo.completeReset("ticket-1", "ABCD-2345", "correct horse battery")

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.ResetRequestNotFound>()
            }
        }

        test("the claim sent to the server is the one retained locally") {
            runTest {
                val storage = InMemoryPasswordResetSecureStorage()
                val service = serviceReturning(AppResult.Success(ticket()))
                val repo = repository(service, storage)

                repo.requestReset("ada@example.com")

                val retainedClaim = storage.read(PasswordResetRepositoryImpl.CLAIM_KEY)
                retainedClaim.shouldNotBeNull()
                verifySuspend { service.requestPasswordReset("ada@example.com", retainedClaim) }
            }
        }

        test("a failed re-request must not strand a stale ticket paired with a stale claim") {
            runTest {
                val storage = InMemoryPasswordResetSecureStorage()
                val service = mock<AuthServicePublic>()
                everySuspend { service.requestPasswordReset(any(), any()) } sequentiallyReturns
                    listOf(
                        AppResult.Success(ticket("ticket-1")),
                        AppResult.Failure(TransportError.NetworkUnavailable()),
                    )
                val repo = repository(service, storage)

                repo.requestReset("ada@example.com") // succeeds: CLAIM_KEY=claim-A, TICKET_KEY=ticket-1
                repo.requestReset("ada@example.com") // overwrites CLAIM_KEY with claim-B, then fails

                // ticket-1's server record is paired with claim-A, which is gone. Resuming ticket-1
                // would send claim-B and can never succeed — the honest state is "no resumable request".
                repo.resumableTicketId() shouldBe null
            }
        }

        test("requestReset failure leaves the claim persisted but the ticket cleared") {
            runTest {
                val storage = InMemoryPasswordResetSecureStorage()
                val service = mock<AuthServicePublic>()
                everySuspend { service.requestPasswordReset(any(), any()) } returns
                    AppResult.Failure(TransportError.NetworkUnavailable())
                val repo = repository(service, storage)

                repo.requestReset("ada@example.com")

                storage.read(PasswordResetRepositoryImpl.CLAIM_KEY).shouldNotBeNull()
                storage.read(PasswordResetRepositoryImpl.TICKET_KEY) shouldBe null
            }
        }

        test("a subsequent successful request after a failure is not corrupted by the failed attempt") {
            runTest {
                val storage = InMemoryPasswordResetSecureStorage()
                val service = mock<AuthServicePublic>()
                everySuspend { service.requestPasswordReset(any(), any()) } sequentiallyReturns
                    listOf(
                        AppResult.Failure(TransportError.NetworkUnavailable()),
                        AppResult.Success(ticket("ticket-2")),
                    )
                val repo = repository(service, storage)

                repo.requestReset("ada@example.com") // fails
                repo.requestReset("ada@example.com") // succeeds

                storage.read(PasswordResetRepositoryImpl.TICKET_KEY) shouldBe "ticket-2"
            }
        }

        test("completeReset failure retains both keys so a retry with the correct code still works") {
            runTest {
                val storage = InMemoryPasswordResetSecureStorage()
                val service = serviceReturning(AppResult.Success(ticket("ticket-9")))
                everySuspend { service.completePasswordReset(any(), any(), any(), any()) } returns
                    AppResult.Failure(AuthError.ResetCodeIncorrect(attemptsRemaining = 4))
                val repo = repository(service, storage)
                val success = repo.requestReset("ada@example.com").shouldBeInstanceOf<AppResult.Success<PasswordResetTicket>>()

                repo.completeReset(success.data.ticketId, "WRONG-CODE", "correct horse battery")

                storage.read(PasswordResetRepositoryImpl.CLAIM_KEY).shouldNotBeNull()
                storage.read(PasswordResetRepositoryImpl.TICKET_KEY) shouldBe success.data.ticketId
            }
        }

        test("two requests mint different claims") {
            runTest {
                val storage = InMemoryPasswordResetSecureStorage()
                val repo = repository(serviceReturning(AppResult.Success(ticket())), storage)

                repo.requestReset("ada@example.com")
                val first = storage.read(PasswordResetRepositoryImpl.CLAIM_KEY)
                repo.requestReset("ada@example.com")

                storage.read(PasswordResetRepositoryImpl.CLAIM_KEY) shouldNotBe first
            }
        }

        test("observeStatus emits the mapped status events and completes honestly") {
            runTest {
                val service = mock<AuthServicePublic>()
                every { service.observePasswordResetStatus(any()) } returns
                    flowOf(
                        RpcEvent.Data(PasswordResetStatusEvent(PasswordResetStatus.PENDING, expiresAt = 100L)),
                        RpcEvent.Data(PasswordResetStatusEvent(PasswordResetStatus.APPROVED, expiresAt = 100L)),
                        RpcEvent.Complete,
                    )
                val repo = repository(service, InMemoryPasswordResetSecureStorage())

                val events = repo.observeStatus("ticket-1").toList()

                events shouldBe
                    listOf(
                        PasswordResetStatusEvent(PasswordResetStatus.PENDING, expiresAt = 100L),
                        PasswordResetStatusEvent(PasswordResetStatus.APPROVED, expiresAt = 100L),
                    )
            }
        }

        test("observeStatus throws a typed failure on a server-surfaced RpcEvent.Error") {
            runTest {
                val service = mock<AuthServicePublic>()
                every { service.observePasswordResetStatus(any()) } returns
                    flowOf(RpcEvent.Error(TransportError.NetworkUnavailable()))
                val repo = repository(service, InMemoryPasswordResetSecureStorage())

                shouldThrow<PasswordResetStatusStreamFailure> {
                    repo.observeStatus("ticket-1").toList()
                }
            }
        }

        test("fetchStatus returns the first status emission of the same watch") {
            runTest {
                val service = mock<AuthServicePublic>()
                every { service.observePasswordResetStatus(any()) } returns
                    flowOf(
                        RpcEvent.Data(PasswordResetStatusEvent(PasswordResetStatus.PENDING, expiresAt = 100L)),
                        RpcEvent.Data(PasswordResetStatusEvent(PasswordResetStatus.APPROVED, expiresAt = 100L)),
                    )
                val repo = repository(service, InMemoryPasswordResetSecureStorage())

                repo.fetchStatus("ticket-1") shouldBe PasswordResetStatusEvent(PasswordResetStatus.PENDING, expiresAt = 100L)
            }
        }

        test("fetchStatus never throws — a transport fault resolves to null") {
            runTest {
                val service = mock<AuthServicePublic>()
                every { service.observePasswordResetStatus(any()) } returns
                    flowOf(RpcEvent.Error(TransportError.NetworkUnavailable()))
                val repo = repository(service, InMemoryPasswordResetSecureStorage())

                repo.fetchStatus("ticket-1") shouldBe null
            }
        }

        test("resetRootPassword delegates directly to the service — no claim involved") {
            runTest {
                val service = mock<AuthServicePublic>()
                everySuspend { service.resetRootPassword(any(), any()) } returns AppResult.Success(Unit)
                val repo = repository(service, InMemoryPasswordResetSecureStorage())

                val result = repo.resetRootPassword("root-token", "correct horse battery")

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { service.resetRootPassword("root-token", "correct horse battery") }
            }
        }
    })

/** Stateful in-memory [SecureStorage] so persistence round-trips are exercised, not mocked. */
private class InMemoryPasswordResetSecureStorage : SecureStorage {
    private val store = mutableMapOf<String, String>()

    override suspend fun save(
        key: String,
        value: String,
    ) {
        store[key] = value
    }

    override suspend fun read(key: String): String? = store[key]

    override suspend fun delete(key: String) {
        store.remove(key)
    }

    override suspend fun clear() {
        store.clear()
    }
}
