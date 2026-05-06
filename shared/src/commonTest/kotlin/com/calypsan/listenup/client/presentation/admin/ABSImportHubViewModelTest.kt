package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.calypsan.listenup.client.core.error.ErrorBus

@OptIn(ExperimentalCoroutinesApi::class)
class ABSImportHubViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val testImport =
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

    private fun createUser(
        absUserId: String = "abs-user-1",
        absUsername: String = "testuser",
        isMapped: Boolean = false,
    ) = ABSImportUser(
        absUserId = absUserId,
        absUsername = absUsername,
        isMapped = isMapped,
    )

    private fun createBook(
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

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createMockedApi(): ABSImportApiContract {
        val api: ABSImportApiContract = mock()
        everySuspend { api.listImports() } returns Success(emptyList())
        everySuspend { api.getImport("import-1") } returns Success(testImport)
        everySuspend { api.listImportUsers("import-1", any()) } returns Success(listOf(createUser()))
        everySuspend { api.listImportBooks("import-1", any()) } returns Success(listOf(createBook()))
        return api
    }

    // ========== mapBook in-flight state ==========

    @Test
    fun `mapBook adds absMediaId to mappingInFlightBooks during request`() =
        runTest {
            val api = createMockedApi()
            val mappedBook = createBook(isMapped = true)
            everySuspend { api.mapBook("import-1", "abs-book-1", "lu-book-1") } returns Success(mappedBook)

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
            val inFlight = assertIs<ABSImportHubUiState.Ready>(vm.hubState.value)
            assertTrue(inFlight.mappingInFlightBooks.contains("abs-book-1"))

            advanceUntilIdle()

            // After completion, in-flight set should be cleared
            val after = assertIs<ABSImportHubUiState.Ready>(vm.hubState.value)
            assertFalse(after.mappingInFlightBooks.contains("abs-book-1"))
        }

    @Test
    fun `mapBook clears in-flight on failure`() =
        runTest {
            val api = createMockedApi()
            everySuspend { api.mapBook("import-1", "abs-book-1", "lu-book-1") } returns
                Failure(Exception("Server error"))

            val vm = ABSImportHubViewModel(api, mock(), mock(), errorBus = ErrorBus())
            advanceUntilIdle()

            vm.openImport("import-1")
            advanceUntilIdle()

            vm.mapBook("abs-book-1", "lu-book-1")
            val inFlight = assertIs<ABSImportHubUiState.Ready>(vm.hubState.value)
            assertTrue(inFlight.mappingInFlightBooks.contains("abs-book-1"))

            advanceUntilIdle()

            val after = assertIs<ABSImportHubUiState.Ready>(vm.hubState.value)
            assertFalse(after.mappingInFlightBooks.contains("abs-book-1"))
            assertEquals("Failed to map book", after.error)
        }

    // ========== mapUser in-flight state ==========

    @Test
    fun `mapUser adds absUserId to mappingInFlightUsers during request`() =
        runTest {
            val api = createMockedApi()
            val mappedUser = createUser(isMapped = true)
            everySuspend { api.mapUser("import-1", "abs-user-1", "lu-user-1") } returns Success(mappedUser)

            val vm = ABSImportHubViewModel(api, mock(), mock(), errorBus = ErrorBus())
            advanceUntilIdle()

            vm.openImport("import-1")
            advanceUntilIdle()

            vm.mapUser("abs-user-1", "lu-user-1")
            val inFlight = assertIs<ABSImportHubUiState.Ready>(vm.hubState.value)
            assertTrue(inFlight.mappingInFlightUsers.contains("abs-user-1"))

            advanceUntilIdle()

            val after = assertIs<ABSImportHubUiState.Ready>(vm.hubState.value)
            assertFalse(after.mappingInFlightUsers.contains("abs-user-1"))
        }

    @Test
    fun `mapUser clears in-flight on failure`() =
        runTest {
            val api = createMockedApi()
            everySuspend { api.mapUser("import-1", "abs-user-1", "lu-user-1") } returns
                Failure(Exception("Server error"))

            val vm = ABSImportHubViewModel(api, mock(), mock(), errorBus = ErrorBus())
            advanceUntilIdle()

            vm.openImport("import-1")
            advanceUntilIdle()

            vm.mapUser("abs-user-1", "lu-user-1")
            val inFlight = assertIs<ABSImportHubUiState.Ready>(vm.hubState.value)
            assertTrue(inFlight.mappingInFlightUsers.contains("abs-user-1"))

            advanceUntilIdle()

            val after = assertIs<ABSImportHubUiState.Ready>(vm.hubState.value)
            assertFalse(after.mappingInFlightUsers.contains("abs-user-1"))
            assertEquals("Failed to map user", after.error)
        }

    // ========== openImport polling behavior ==========

    @Test
    fun `openImport starts polling when status is analyzing`() =
        runTest {
            val api = createMockedApi()
            val analyzingImport = testImport.copy(status = "analyzing")
            val completedImport = testImport.copy(status = "active")

            // openImport will get "analyzing" → triggers startAnalysisPolling
            everySuspend { api.getImport("import-1") } returns Success(analyzingImport)

            val vm = ABSImportHubViewModel(api, mock(), mock(), errorBus = ErrorBus())
            advanceUntilIdle() // drain init (loadImports)

            vm.openImport("import-1")
            // Use runCurrent() — NOT advanceUntilIdle() — to execute the openImport
            // coroutine without advancing virtual time into the infinite polling loop
            testScheduler.runCurrent()

            val analyzing = assertIs<ABSImportHubUiState.Ready>(vm.hubState.value)
            assertEquals("analyzing", analyzing.import.status)

            // Re-stub so the next poll returns "active" — polling loop exits naturally
            everySuspend { api.getImport("import-1") } returns Success(completedImport)

            // Advance past the 3-second polling interval so the poll fires
            testScheduler.advanceTimeBy(3_100)
            testScheduler.runCurrent()

            val completed = assertIs<ABSImportHubUiState.Ready>(vm.hubState.value)
            assertEquals("active", completed.import.status)
        }
}
