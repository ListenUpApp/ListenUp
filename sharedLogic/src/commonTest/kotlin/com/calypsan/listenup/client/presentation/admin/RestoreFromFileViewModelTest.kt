package com.calypsan.listenup.client.presentation.admin

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.BackupRepository
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreFromFileViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun fakeFileSource(name: String): FileSource =
            object : FileSource {
                override val filename: String = name
                override val size: Long? = null

                override fun openChannel(): ByteReadChannel = ByteReadChannel.Empty
            }

        val summary =
            BackupSummary(
                id = BackupId("backup-x"),
                createdAt = 1L,
                sizeBytes = 1L,
                includesImages = false,
                schemaVersion = "37",
                appVersion = "0.6.0",
                bookCount = 0,
                userCount = 0,
            )

        test("a successful upload emits a navigate-to-restore event with the staged id") {
            runTest {
                val repo = mock<BackupRepository>()
                everySuspend { repo.uploadBackup(any()) } returns AppResult.Success(summary)
                val vm = RestoreFromFileViewModel(repo, ErrorBus())
                vm.navigation.test {
                    vm.onFilePicked(fakeFileSource("from-nas.listenup.zip"))
                    advanceUntilIdle()
                    awaitItem() shouldBe BackupId("backup-x")
                }
            }
        }

        test("a failed upload transitions to Error and emits to the error bus") {
            runTest {
                val repo = mock<BackupRepository>()
                everySuspend { repo.uploadBackup(any()) } returns AppResult.Failure(TransportError.Timeout())
                val errorBus = ErrorBus()
                val vm = RestoreFromFileViewModel(repo, errorBus)
                // Keep the WhileSubscribed `state` hot so its value reflects the VM's updates.
                backgroundScope.launch { vm.state.collect { } }
                errorBus.errors.test {
                    vm.onFilePicked(fakeFileSource("x.listenup.zip"))
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<TransportError.Timeout>()
                }
                vm.state.value.shouldBeInstanceOf<RestoreFromFileUiState.Error>()
            }
        }
    })
