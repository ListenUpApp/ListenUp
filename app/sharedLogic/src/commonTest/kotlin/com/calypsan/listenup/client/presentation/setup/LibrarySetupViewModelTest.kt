package com.calypsan.listenup.client.presentation.setup

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.AccessMode
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolder
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

/**
 * Tests for [LibrarySetupViewModel].
 *
 * Covers:
 * - Setup status check on init → `getSetupStatus()` path
 * - Filesystem browsing → `browseFilesystem()` path; parentPath/isRoot derived client-side
 * - [completeSetup] adds each selected folder via [addFolder], triggers the scan,
 *   then emits [LibrarySetupNavAction.Finished]
 * - [completeSetup] with no selected paths sets an error and does not finish
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

        fun makeService(): LibraryAdminService =
            mock<LibraryAdminService> {
                // Default: scanLibrary is a no-op success unless a test overrides it.
                everySuspend { scanLibrary() } returns AppResult.Success(Unit)
            }

        fun makeLibraryFolder(
            path: String = "/audio/a",
            libraryId: String = "lib-001",
        ): LibraryFolder =
            LibraryFolder(
                id = FolderId("folder-001"),
                libraryId = LibraryId(libraryId),
                rootPath = path,
                createdAt = 1_700_000_000_000L,
            )

        fun makeLibrary(
            id: String = "lib-001",
            name: String = "My Library",
        ): Library =
            Library(
                id = LibraryId(id),
                name = name,
                folders = emptyList(),
                metadataPrecedence = "embedded,abs,sidecar",
                accessMode = AccessMode.SHARED,
                createdByUserId = null,
                createdAt = 1_700_000_000_000L,
            )

        // ── init: getSetupStatus ──────────────────────────────────────────────

        test("init calls getSetupStatus and sets needsSetup=false when no setup needed") {
            runTest {
                val service = makeService()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false))

                val vm = LibrarySetupViewModel(RpcChannel.forTest(service), ErrorBus(), CoroutineScope(testDispatcher))
                advanceUntilIdle()

                vm.state.value.isCheckingStatus shouldBe false
                vm.state.value.needsSetup shouldBe false
            }
        }

        test("init calls getSetupStatus and loads root directory when needsSetup=true") {
            runTest {
                val service = makeService()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = true))
                everySuspend { service.browseFilesystem(any()) } returns
                    AppResult.Success(emptyList())

                val vm = LibrarySetupViewModel(RpcChannel.forTest(service), ErrorBus(), CoroutineScope(testDispatcher))
                advanceUntilIdle()

                vm.state.value.needsSetup shouldBe true
                verifySuspend(VerifyMode.atLeast(1)) { service.browseFilesystem("/") }
            }
        }

        test("getSetupStatus failure sets error in state") {
            runTest {
                val service = makeService()
                val errorBus = ErrorBus()
                val error = InternalError()
                everySuspend { service.getSetupStatus() } returns AppResult.Failure(error)

                val vm = LibrarySetupViewModel(RpcChannel.forTest(service), errorBus, CoroutineScope(testDispatcher))
                advanceUntilIdle()

                vm.state.value.isCheckingStatus shouldBe false
                vm.state.value.error shouldBe error.message
            }
        }

        // ── loadDirectory ────────────────────────────────────────────────────

        test("loadDirectory sets currentPath and directories from RPC response") {
            runTest {
                val service = makeService()
                val entries =
                    listOf(
                        DirectoryEntry(name = "audiobooks", path = "/data/audiobooks", hasChildren = true, itemCount = 12),
                        DirectoryEntry(name = "music", path = "/data/music", hasChildren = false, itemCount = 0),
                    )
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false))
                everySuspend { service.browseFilesystem("/data") } returns
                    AppResult.Success(entries)

                val vm = LibrarySetupViewModel(RpcChannel.forTest(service), ErrorBus(), CoroutineScope(testDispatcher))
                advanceUntilIdle()

                vm.loadDirectory("/data")
                advanceUntilIdle()

                vm.state.value.currentPath shouldBe "/data"
                vm.state.value.directories shouldBe entries
                vm.state.value.isLoadingDirectories shouldBe false
            }
        }

        test("loadDirectory at root path sets isRoot=true and parentPath=null") {
            runTest {
                val service = makeService()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false))
                everySuspend { service.browseFilesystem("/") } returns
                    AppResult.Success(emptyList())

                val vm = LibrarySetupViewModel(RpcChannel.forTest(service), ErrorBus(), CoroutineScope(testDispatcher))
                advanceUntilIdle()

                vm.loadDirectory("/")
                advanceUntilIdle()

                vm.state.value.isRoot shouldBe true
                vm.state.value.parentPath shouldBe null
            }
        }

        test("loadDirectory at nested path derives parentPath and isRoot=false") {
            runTest {
                val service = makeService()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false))
                everySuspend { service.browseFilesystem("/data/audiobooks") } returns
                    AppResult.Success(emptyList())

                val vm = LibrarySetupViewModel(RpcChannel.forTest(service), ErrorBus(), CoroutineScope(testDispatcher))
                advanceUntilIdle()

                vm.loadDirectory("/data/audiobooks")
                advanceUntilIdle()

                vm.state.value.isRoot shouldBe false
                vm.state.value.parentPath shouldBe "/data"
            }
        }

        test("loadDirectory failure sets error in state") {
            runTest {
                val service = makeService()
                val error = InternalError()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false))
                everySuspend { service.browseFilesystem(any()) } returns AppResult.Failure(error)

                val vm = LibrarySetupViewModel(RpcChannel.forTest(service), ErrorBus(), CoroutineScope(testDispatcher))
                advanceUntilIdle()
                vm.loadDirectory("/data")
                advanceUntilIdle()

                vm.state.value.isLoadingDirectories shouldBe false
                vm.state.value.error shouldBe error.message
            }
        }

        // ── completeSetup ────────────────────────────────────────────────────

        test("completeSetup adds each selected folder then scans then emits Finished") {
            runTest {
                val service = makeService()
                val folder = makeLibraryFolder()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false))
                everySuspend { service.addFolder(any()) } returns AppResult.Success(folder)
                everySuspend { service.scanLibrary() } returns AppResult.Success(Unit)

                val appScope = CoroutineScope(testDispatcher)
                val vm = LibrarySetupViewModel(RpcChannel.forTest(service), ErrorBus(), appScope)
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

        test("completeSetup with no selected paths sets an error and does not finish") {
            runTest {
                val service = makeService()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false))

                val vm = LibrarySetupViewModel(RpcChannel.forTest(service), ErrorBus(), CoroutineScope(testDispatcher))
                advanceUntilIdle()

                vm.navActions.test {
                    vm.completeSetup()
                    advanceUntilIdle()

                    expectNoEvents()
                }

                vm.state.value.error shouldNotBe null
            }
        }

        test("completeSetup addFolder failure sets error and does not finish") {
            runTest {
                val service = makeService()
                val error = InternalError()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false))
                everySuspend { service.addFolder(any()) } returns AppResult.Failure(error)

                val vm = LibrarySetupViewModel(RpcChannel.forTest(service), ErrorBus(), CoroutineScope(testDispatcher))
                advanceUntilIdle()
                vm.selectPath("/audio/a")

                vm.navActions.test {
                    vm.completeSetup()
                    advanceUntilIdle()

                    expectNoEvents()
                }

                vm.state.value.isCreatingLibrary shouldBe false
                vm.state.value.error shouldBe error.message
            }
        }

        // ── path selection ───────────────────────────────────────────────────

        test("togglePath adds path when not selected") {
            runTest {
                val service = makeService()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false))

                val vm = LibrarySetupViewModel(RpcChannel.forTest(service), ErrorBus(), CoroutineScope(testDispatcher))
                advanceUntilIdle()

                vm.togglePath("/data/audiobooks")

                vm.state.value.selectedPaths shouldBe setOf("/data/audiobooks")
            }
        }

        test("togglePath removes path when already selected") {
            runTest {
                val service = makeService()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false))

                val vm = LibrarySetupViewModel(RpcChannel.forTest(service), ErrorBus(), CoroutineScope(testDispatcher))
                advanceUntilIdle()

                vm.togglePath("/data/audiobooks")
                vm.togglePath("/data/audiobooks")

                vm.state.value.selectedPaths shouldBe emptySet()
            }
        }

        test("selectPath adds path to selection") {
            runTest {
                val service = makeService()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false))

                val vm = LibrarySetupViewModel(RpcChannel.forTest(service), ErrorBus(), CoroutineScope(testDispatcher))
                advanceUntilIdle()

                vm.selectPath("/audio/a")
                vm.selectPath("/audio/b")

                vm.state.value.selectedPaths shouldBe setOf("/audio/a", "/audio/b")
            }
        }

        test("clearSelection empties selectedPaths") {
            runTest {
                val service = makeService()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false))

                val vm = LibrarySetupViewModel(RpcChannel.forTest(service), ErrorBus(), CoroutineScope(testDispatcher))
                advanceUntilIdle()

                vm.selectPath("/audio/a")
                vm.clearSelection()

                vm.state.value.selectedPaths shouldBe emptySet()
            }
        }
    })
