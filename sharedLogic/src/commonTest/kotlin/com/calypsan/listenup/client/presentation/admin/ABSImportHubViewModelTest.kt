package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.data.remote.ABSImportBook
import com.calypsan.listenup.client.data.remote.ABSImportResponse
import com.calypsan.listenup.client.data.remote.ABSImportUser
import com.calypsan.listenup.client.data.remote.MappingFilter
import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.domain.repository.SyncRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

@OptIn(ExperimentalCoroutinesApi::class)
class ABSImportHubViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        val testImport =
            ABSImportResponse(
                id = "import-1",
                name = "Test Import",
                backupPath = "/test/backup.zip",
                status = "active",
                createdAt = "2024-01-01T00:00:00Z",
                updatedAt = "2024-01-01T00:00:00Z",
                totalUsers = 1,
                totalBooks = 1,
                totalSessions = 0,
                usersMapped = 0,
                booksMapped = 0,
                sessionsImported = 0,
            )

        fun createUser(
            absUserId: String = "abs-user-1",
            absUsername: String = "testuser",
            isMapped: Boolean = false,
        ) = ABSImportUser(
            absUserId = absUserId,
            absUsername = absUsername,
            isMapped = isMapped,
        )

        fun createBook(
            absMediaId: String = "abs-book-1",
            absTitle: String = "Test Book",
            absAuthor: String = "Author",
            isMapped: Boolean = false,
        ) = ABSImportBook(
            absMediaId = absMediaId,
            absTitle = absTitle,
            absAuthor = absAuthor,
            isMapped = isMapped,
        )

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun createMockedApi(): ABSImportApiContract {
            val api: ABSImportApiContract = mock()
            everySuspend { api.listImports() } returns AppResult.Success(emptyList())
            everySuspend { api.getImport("import-1") } returns AppResult.Success(testImport)
            everySuspend { api.listImportUsers("import-1", any()) } returns AppResult.Success(listOf(createUser()))
            everySuspend { api.listImportBooks("import-1", any()) } returns AppResult.Success(listOf(createBook()))
            return api
        }

        // ========== mapBook in-flight state ==========

        test("mapBook adds absMediaId to mappingInFlightBooks during request") {
            runTest {
                val api = createMockedApi()
                val mappedBook = createBook(isMapped = true)
                everySuspend { api.mapBook("import-1", "abs-book-1", "lu-book-1") } returns AppResult.Success(mappedBook)

                val vm = ABSImportHubViewModel(api, mock(), mock(), errorBus = ErrorBus())
                advanceUntilIdle()

                // Open import to set importId
                vm.openImport("import-1")
                advanceUntilIdle()

                // Switch to books tab to load books
                vm.setBooksFilter(MappingFilter.ALL)
                advanceUntilIdle()

                // Trigger mapBook — in-flight set should be populated synchronously
                vm.mapBook("abs-book-1", "lu-book-1")
                val inFlight = vm.hubState.value.shouldBeInstanceOf<ABSImportHubUiState.Ready>()
                inFlight.mappingInFlightBooks.contains("abs-book-1") shouldBe true

                advanceUntilIdle()

                // After completion, in-flight set should be cleared
                val after = vm.hubState.value.shouldBeInstanceOf<ABSImportHubUiState.Ready>()
                after.mappingInFlightBooks.contains("abs-book-1") shouldBe false
            }
        }

        test("mapBook clears in-flight on failure") {
            runTest {
                val api = createMockedApi()
                everySuspend { api.mapBook("import-1", "abs-book-1", "lu-book-1") } returns
                    Failure(Exception("Server error"))

                val vm = ABSImportHubViewModel(api, mock(), mock(), errorBus = ErrorBus())
                advanceUntilIdle()

                vm.openImport("import-1")
                advanceUntilIdle()

                vm.mapBook("abs-book-1", "lu-book-1")
                val inFlight = vm.hubState.value.shouldBeInstanceOf<ABSImportHubUiState.Ready>()
                inFlight.mappingInFlightBooks.contains("abs-book-1") shouldBe true

                advanceUntilIdle()

                val after = vm.hubState.value.shouldBeInstanceOf<ABSImportHubUiState.Ready>()
                after.mappingInFlightBooks.contains("abs-book-1") shouldBe false
                after.error shouldBe "Failed to map book"
            }
        }

        // ========== mapUser in-flight state ==========

        test("mapUser adds absUserId to mappingInFlightUsers during request") {
            runTest {
                val api = createMockedApi()
                val mappedUser = createUser(isMapped = true)
                everySuspend { api.mapUser("import-1", "abs-user-1", "lu-user-1") } returns AppResult.Success(mappedUser)

                val vm = ABSImportHubViewModel(api, mock(), mock(), errorBus = ErrorBus())
                advanceUntilIdle()

                vm.openImport("import-1")
                advanceUntilIdle()

                vm.mapUser("abs-user-1", "lu-user-1")
                val inFlight = vm.hubState.value.shouldBeInstanceOf<ABSImportHubUiState.Ready>()
                inFlight.mappingInFlightUsers.contains("abs-user-1") shouldBe true

                advanceUntilIdle()

                val after = vm.hubState.value.shouldBeInstanceOf<ABSImportHubUiState.Ready>()
                after.mappingInFlightUsers.contains("abs-user-1") shouldBe false
            }
        }

        test("mapUser clears in-flight on failure") {
            runTest {
                val api = createMockedApi()
                everySuspend { api.mapUser("import-1", "abs-user-1", "lu-user-1") } returns
                    Failure(Exception("Server error"))

                val vm = ABSImportHubViewModel(api, mock(), mock(), errorBus = ErrorBus())
                advanceUntilIdle()

                vm.openImport("import-1")
                advanceUntilIdle()

                vm.mapUser("abs-user-1", "lu-user-1")
                val inFlight = vm.hubState.value.shouldBeInstanceOf<ABSImportHubUiState.Ready>()
                inFlight.mappingInFlightUsers.contains("abs-user-1") shouldBe true

                advanceUntilIdle()

                val after = vm.hubState.value.shouldBeInstanceOf<ABSImportHubUiState.Ready>()
                after.mappingInFlightUsers.contains("abs-user-1") shouldBe false
                after.error shouldBe "Failed to map user"
            }
        }

        // ========== openImport polling behavior ==========

        test("openImport starts polling when status is analyzing") {
            runTest {
                val api = createMockedApi()
                val analyzingImport = testImport.copy(status = "analyzing")
                val completedImport = testImport.copy(status = "active")

                // openImport will get "analyzing" → triggers startAnalysisPolling
                everySuspend { api.getImport("import-1") } returns AppResult.Success(analyzingImport)

                val vm = ABSImportHubViewModel(api, mock(), mock(), errorBus = ErrorBus())
                advanceUntilIdle() // drain init (loadImports)

                vm.openImport("import-1")
                // Use runCurrent() — NOT advanceUntilIdle() — to execute the openImport
                // coroutine without advancing virtual time into the infinite polling loop
                testScheduler.runCurrent()

                val analyzing = vm.hubState.value.shouldBeInstanceOf<ABSImportHubUiState.Ready>()
                analyzing.import.status shouldBe "analyzing"

                // Re-stub so the next poll returns "active" — polling loop exits naturally
                everySuspend { api.getImport("import-1") } returns AppResult.Success(completedImport)

                // Advance past the 3-second polling interval so the poll fires
                testScheduler.advanceTimeBy(3_100)
                testScheduler.runCurrent()

                val completed = vm.hubState.value.shouldBeInstanceOf<ABSImportHubUiState.Ready>()
                completed.import.status shouldBe "active"
            }
        }
    })
