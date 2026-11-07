package com.calypsan.listenup.client.presentation.setup

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.api.dto.LibraryFolder
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

/**
 * Tests for [LibrarySetupViewModel].
 *
 * Covers:
 * - The setup check on init: NotNeeded, Browsing (root loaded), or CheckFailed — never a spinner
 *   that outlives a failure — and the retry out of CheckFailed
 * - Filesystem browsing; parentPath/isRoot derived client-side; the newest browse wins
 * - [completeSetup] registers each folder, scans, emits Finished — exactly once, however often tapped
 *
 * Dispatches through a [RpcChannel.forTest] over a Mokkery [LibraryAdminService] stub.
 * [ErrorBus] is a final class — tests observe its [ErrorBus.errors] flow directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySetupViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ── Helpers ──────────────────────────────────────────────────────────────

        fun makeService(needsSetup: Boolean = true): LibraryAdminService =
            mock<LibraryAdminService> {
                // Default: scanLibrary is a no-op success unless a test overrides it.
                everySuspend { scanLibrary() } returns AppResult.Success(Unit)
                everySuspend { getSetupStatus() } returns AppResult.Success(SetupStatus(needsSetup = needsSetup))
                everySuspend { browseFilesystem(any()) } returns AppResult.Success(emptyList())
            }

        fun makeLibraryFolder(path: String = "/audio/a"): LibraryFolder =
            LibraryFolder(
                id = FolderId("folder-001"),
                libraryId = LibraryId("lib-001"),
                rootPath = path,
                createdAt = 1_700_000_000_000L,
            )

        fun entry(path: String): DirectoryEntry =
            DirectoryEntry(name = path.substringAfterLast('/'), path = path, hasChildren = false, itemCount = 0)

        fun LibrarySetupViewModel.browsing(): LibrarySetupUiState.Browsing = state.value.shouldBeInstanceOf<LibrarySetupUiState.Browsing>()

        fun vmFor(
            service: LibraryAdminService,
            errorBus: ErrorBus = ErrorBus(),
        ): LibrarySetupViewModel = LibrarySetupViewModel(RpcChannel.forTest(service), errorBus, CoroutineScope(testDispatcher))

        // ── init: getSetupStatus ──────────────────────────────────────────────

        test("starts in Checking while the setup status is asked for") {
            runTest {
                val vm = vmFor(makeService())

                vm.state.value shouldBe LibrarySetupUiState.Checking
            }
        }

        test("a server with folders resolves to NotNeeded") {
            runTest {
                val vm = vmFor(makeService(needsSetup = false))
                advanceUntilIdle()

                vm.state.value shouldBe LibrarySetupUiState.NotNeeded
            }
        }

        test("a server that needs setup resolves to Browsing with the root loaded") {
            runTest {
                val service = makeService()
                everySuspend { service.browseFilesystem("/") } returns AppResult.Success(listOf(entry("/data")))

                val vm = vmFor(service)
                advanceUntilIdle()

                val browsing = vm.browsing()
                browsing.currentPath shouldBe "/"
                browsing.directories shouldBe listOf(entry("/data"))
                browsing.isLoadingDirectories shouldBe false
            }
        }

        test("a failed check is CheckFailed carrying the typed error, not a spinner") {
            runTest {
                val service = makeService()
                val error = InternalError()
                everySuspend { service.getSetupStatus() } returns AppResult.Failure(error)

                val vm = vmFor(service)
                advanceUntilIdle()

                vm.state.value shouldBe LibrarySetupUiState.CheckFailed(error)
            }
        }

        test("retry from CheckFailed checks again and reaches Browsing") {
            runTest {
                val service = makeService()
                val error = InternalError()
                everySuspend { service.getSetupStatus() } returns AppResult.Failure(error)
                val vm = vmFor(service)
                advanceUntilIdle()

                everySuspend { service.getSetupStatus() } returns AppResult.Success(SetupStatus(needsSetup = true))
                vm.state.test {
                    awaitItem() shouldBe LibrarySetupUiState.CheckFailed(error)
                    vm.retryStatusCheck()
                    awaitItem() shouldBe LibrarySetupUiState.Checking
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<LibrarySetupUiState.Browsing>()
                    cancelAndIgnoreRemainingEvents()
                }
                vm.browsing().isLoadingDirectories shouldBe false
            }
        }

        test("retry is a no-op while browsing, so the selection survives") {
            runTest {
                val service = makeService()
                val vm = vmFor(service)
                advanceUntilIdle()
                vm.selectPath("/audio/a")

                vm.retryStatusCheck()
                advanceUntilIdle()

                vm.browsing().selectedPaths shouldBe setOf("/audio/a")
                verifySuspend(VerifyMode.exactly(1)) { service.getSetupStatus() }
            }
        }

        // ── loadDirectory ────────────────────────────────────────────────────

        test("loadDirectory sets currentPath and directories from RPC response") {
            runTest {
                val service = makeService()
                val entries = listOf(entry("/data/audiobooks"), entry("/data/music"))
                everySuspend { service.browseFilesystem("/data") } returns AppResult.Success(entries)

                val vm = vmFor(service)
                advanceUntilIdle()
                vm.loadDirectory("/data")
                advanceUntilIdle()

                vm.browsing().currentPath shouldBe "/data"
                vm.browsing().directories shouldBe entries
                vm.browsing().isLoadingDirectories shouldBe false
            }
        }

        test("the root has no parent; a nested path derives its parent") {
            runTest {
                val vm = vmFor(makeService())
                advanceUntilIdle()

                vm.browsing().isRoot shouldBe true
                vm.browsing().parentPath shouldBe null

                vm.loadDirectory("/data/audiobooks")
                advanceUntilIdle()

                vm.browsing().isRoot shouldBe false
                vm.browsing().parentPath shouldBe "/data"
            }
        }

        test("overlapping loads: the newest browse wins even when the older reply lands last") {
            runTest {
                val service = makeService()
                everySuspend { service.browseFilesystem("/slow") } calls {
                    delay(1_000)
                    AppResult.Success(listOf(entry("/slow/old")))
                }
                everySuspend { service.browseFilesystem("/fast") } returns AppResult.Success(listOf(entry("/fast/new")))
                val vm = vmFor(service)
                advanceUntilIdle()

                vm.loadDirectory("/slow")
                vm.loadDirectory("/fast")
                advanceUntilIdle()

                vm.browsing().currentPath shouldBe "/fast"
                vm.browsing().directories shouldBe listOf(entry("/fast/new"))
                vm.browsing().isLoadingDirectories shouldBe false
            }
        }

        test("loadDirectory failure keeps the picker and carries the error inline") {
            runTest {
                val service = makeService()
                val error = InternalError()
                everySuspend { service.browseFilesystem("/data") } returns AppResult.Failure(error)

                val vm = vmFor(service)
                advanceUntilIdle()
                vm.loadDirectory("/data")
                advanceUntilIdle()

                vm.browsing().isLoadingDirectories shouldBe false
                vm.browsing().currentPath shouldBe "/"
                vm.browsing().error shouldBe error

                vm.clearError()
                vm.browsing().error shouldBe null
            }
        }

        // ── completeSetup ────────────────────────────────────────────────────

        test("completeSetup adds each selected folder then scans then emits Finished") {
            runTest {
                val service = makeService()
                everySuspend { service.addFolder(any()) } returns AppResult.Success(makeLibraryFolder())
                val vm = vmFor(service)
                advanceUntilIdle()

                vm.selectPath("/audio/a")
                vm.selectPath("/audio/b")

                vm.navActions.test {
                    vm.completeSetup()
                    advanceUntilIdle()

                    awaitItem() shouldBe LibrarySetupNavAction.Finished
                }

                verifySuspend { service.addFolder("/audio/a") }
                verifySuspend { service.addFolder("/audio/b") }
                verifySuspend { service.scanLibrary() }
            }
        }

        test("completeSetup called twice while the first is in flight registers the folder once") {
            runTest {
                val service = makeService()
                everySuspend { service.addFolder(any()) } calls {
                    delay(1_000)
                    AppResult.Success(makeLibraryFolder())
                }
                val vm = vmFor(service)
                advanceUntilIdle()
                vm.selectPath("/audio/a")

                vm.navActions.test {
                    vm.completeSetup()
                    vm.completeSetup()
                    advanceUntilIdle()

                    awaitItem() shouldBe LibrarySetupNavAction.Finished
                    expectNoEvents()
                }

                verifySuspend(VerifyMode.exactly(1)) { service.addFolder("/audio/a") }
                verifySuspend(VerifyMode.exactly(1)) { service.scanLibrary() }
            }
        }

        test("completeSetup with no selected paths does nothing") {
            runTest {
                val service = makeService()
                val vm = vmFor(service)
                advanceUntilIdle()
                val before = vm.browsing()

                vm.navActions.test {
                    vm.completeSetup()
                    advanceUntilIdle()

                    expectNoEvents()
                }

                vm.browsing() shouldBe before
                verifySuspend(VerifyMode.exactly(0)) { service.addFolder(any()) }
            }
        }

        test("completeSetup addFolder failure keeps the picker with the error and does not finish") {
            runTest {
                val service = makeService()
                val error = InternalError()
                everySuspend { service.addFolder(any()) } returns AppResult.Failure(error)
                val vm = vmFor(service)
                advanceUntilIdle()
                vm.selectPath("/audio/a")

                vm.navActions.test {
                    vm.completeSetup()
                    advanceUntilIdle()

                    expectNoEvents()
                }

                vm.browsing().isCreatingLibrary shouldBe false
                vm.browsing().error shouldBe error
                vm.browsing().selectedPaths shouldBe setOf("/audio/a")
            }
        }

        // ── path selection ───────────────────────────────────────────────────

        test("togglePath adds a path, and removes it when toggled again") {
            runTest {
                val vm = vmFor(makeService())
                advanceUntilIdle()

                vm.togglePath("/data/audiobooks")
                vm.browsing().selectedPaths shouldBe setOf("/data/audiobooks")

                vm.togglePath("/data/audiobooks")
                vm.browsing().selectedPaths shouldBe emptySet()
            }
        }

        test("selectPath adds to the selection and clearSelection empties it") {
            runTest {
                val vm = vmFor(makeService())
                advanceUntilIdle()

                vm.selectPath("/audio/a")
                vm.selectPath("/audio/b")
                vm.browsing().selectedPaths shouldBe setOf("/audio/a", "/audio/b")

                vm.clearSelection()
                vm.browsing().selectedPaths shouldBe emptySet()
            }
        }
    })
