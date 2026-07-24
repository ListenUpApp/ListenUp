package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.dto.imports.ImportStatus
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ImportRepository
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * The migrated hub is a thin read-only list over [ImportRepository] (the new `ImportService` stack):
 * load the staged imports, surface failures, and delete a job. The old per-import detail/mapping
 * behaviour moved to the linear `ImportFlow` and is no longer this ViewModel's concern.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ABSImportHubViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        fun summary(id: String) =
            ImportSummary(
                id = ImportId(id),
                createdAt = 1_000L,
                status = ImportStatus.UPLOADED,
                bookCount = 3,
                userCount = 1,
            )

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("init loads the staged imports into Ready") {
            runTest {
                val repo: ImportRepository = mock()
                everySuspend { repo.listImports() } returns AppResult.Success(listOf(summary("import-1")))

                val vm = ABSImportHubViewModel(repo, ErrorBus())
                advanceUntilIdle()

                val ready = vm.listState.value.shouldBeInstanceOf<ABSImportListUiState.Ready>()
                ready.imports.map { it.id } shouldBe listOf(ImportId("import-1"))
            }
        }

        test("an initial load failure surfaces Error carrying the typed AppError") {
            runTest {
                val repo: ImportRepository = mock()
                val error = SyncError.NotFound(domain = "imports", entityId = "x")
                everySuspend { repo.listImports() } returns AppResult.Failure(error)

                val vm = ABSImportHubViewModel(repo, ErrorBus())
                advanceUntilIdle()

                val state = vm.listState.value.shouldBeInstanceOf<ABSImportListUiState.Error>()
                state.error shouldBe error
            }
        }

        test("deleteImport calls the repository and reloads the list") {
            runTest {
                val repo: ImportRepository = mock()
                everySuspend { repo.listImports() } returns AppResult.Success(listOf(summary("a")))
                everySuspend { repo.deleteImport(ImportId("a")) } returns AppResult.Success(Unit)

                val vm = ABSImportHubViewModel(repo, ErrorBus())
                advanceUntilIdle()

                vm.deleteImport(ImportId("a"))
                advanceUntilIdle()

                verifySuspend { repo.deleteImport(ImportId("a")) }
                vm.listState.value.shouldBeInstanceOf<ABSImportListUiState.Ready>()
            }
        }
    })
