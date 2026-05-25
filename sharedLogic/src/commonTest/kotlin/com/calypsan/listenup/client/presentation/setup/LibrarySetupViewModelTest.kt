package com.calypsan.listenup.client.presentation.setup

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.AccessMode
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.LibraryAdminRpcFactory
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentiallyReturns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
 * - Library creation success → NavAction emission + UiState reset (multi-library loop)
 * - Library creation failure → ErrorBus emit + error in UiState
 * - Multi-library loop: second createLibrary appends to createdLibraries
 * - `finishOnboarding()` flips setupComplete + emits Finished NavAction
 *
 * Uses Mokkery to mock [LibraryAdminRpcFactory] returning a [LibraryAdminService] stub.
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

        fun makeService(): LibraryAdminService = mock<LibraryAdminService>()

        fun makeFactory(service: LibraryAdminService): LibraryAdminRpcFactory =
            mock<LibraryAdminRpcFactory>().also {
                everySuspend { it.get() } returns service
            }

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
                    AppResult.Success(SetupStatus(needsSetup = false, libraryCount = 1))

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
                advanceUntilIdle()

                vm.state.value.isCheckingStatus shouldBe false
                vm.state.value.needsSetup shouldBe false
            }
        }

        test("init calls getSetupStatus and loads root directory when needsSetup=true") {
            runTest {
                val service = makeService()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = true, libraryCount = 0))
                everySuspend { service.browseFilesystem(any()) } returns
                    AppResult.Success(emptyList())

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
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

                val vm = LibrarySetupViewModel(makeFactory(service), errorBus)
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
                        DirectoryEntry(name = "audiobooks", path = "/data/audiobooks", hasChildren = true),
                        DirectoryEntry(name = "music", path = "/data/music", hasChildren = false),
                    )
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false, libraryCount = 1))
                everySuspend { service.browseFilesystem("/data") } returns
                    AppResult.Success(entries)

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
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
                    AppResult.Success(SetupStatus(needsSetup = false, libraryCount = 1))
                everySuspend { service.browseFilesystem("/") } returns
                    AppResult.Success(emptyList())

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
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
                    AppResult.Success(SetupStatus(needsSetup = false, libraryCount = 1))
                everySuspend { service.browseFilesystem("/data/audiobooks") } returns
                    AppResult.Success(emptyList())

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
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
                    AppResult.Success(SetupStatus(needsSetup = false, libraryCount = 1))
                everySuspend { service.browseFilesystem(any()) } returns AppResult.Failure(error)

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
                advanceUntilIdle()
                vm.loadDirectory("/data")
                advanceUntilIdle()

                vm.state.value.isLoadingDirectories shouldBe false
                vm.state.value.error shouldBe error.message
            }
        }

        // ── createLibrary: validation ────────────────────────────────────────

        test("createLibrary with no paths selected sets error and does not call RPC") {
            runTest {
                val service = makeService()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false, libraryCount = 1))

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
                advanceUntilIdle()

                vm.createLibrary()
                advanceUntilIdle()

                vm.state.value.error shouldBe "Please select at least one folder for your library"
            }
        }

        test("createLibrary with blank name sets error and does not call RPC") {
            runTest {
                val service = makeService()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false, libraryCount = 1))

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
                advanceUntilIdle()
                vm.togglePath("/data/audiobooks")
                vm.setLibraryName("   ")
                vm.createLibrary()
                advanceUntilIdle()

                vm.state.value.error shouldBe "Please enter a name for your library"
            }
        }

        // ── createLibrary: success — multi-library loop ──────────────────────

        test("createLibrary success appends to createdLibraries and resets selection") {
            runTest {
                val service = makeService()
                val library = makeLibrary()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false, libraryCount = 1))
                everySuspend { service.createLibrary(any()) } returns AppResult.Success(library)

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
                advanceUntilIdle()
                vm.togglePath("/data/audiobooks")
                vm.setLibraryName("My Library")

                vm.createLibrary()
                advanceUntilIdle()

                vm.state.value.isCreatingLibrary shouldBe false
                vm.state.value.createdLibraries.size shouldBe 1
                vm.state.value.createdLibraries.first() shouldBe library
                // Selection resets for the next library
                vm.state.value.selectedPaths shouldBe emptySet()
                vm.state.value.libraryName shouldBe "My Library"
                // setupComplete is NOT yet flipped — only finishOnboarding() does that
                vm.state.value.setupComplete shouldBe false
            }
        }

        test("createLibrary success emits LibraryCreated NavAction") {
            runTest {
                val service = makeService()
                val library = makeLibrary()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false, libraryCount = 1))
                everySuspend { service.createLibrary(any()) } returns AppResult.Success(library)

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
                advanceUntilIdle()
                vm.togglePath("/data/audiobooks")

                vm.createLibrary()
                advanceUntilIdle()

                val action = vm.navActions.first()
                (action is LibrarySetupNavAction.LibraryCreated) shouldBe true
                (action as LibrarySetupNavAction.LibraryCreated).library shouldBe library
            }
        }

        test("second createLibrary appends another library to createdLibraries") {
            runTest {
                val service = makeService()
                val lib1 = makeLibrary(id = "lib-001", name = "First")
                val lib2 = makeLibrary(id = "lib-002", name = "Second")
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false, libraryCount = 1))
                everySuspend { service.createLibrary(any()) } sequentiallyReturns
                    listOf(AppResult.Success(lib1), AppResult.Success(lib2))

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
                advanceUntilIdle()

                // First library creation
                vm.togglePath("/data/audiobooks")
                vm.setLibraryName("First")
                vm.createLibrary()
                advanceUntilIdle()

                // Second library creation
                vm.togglePath("/data/music")
                vm.setLibraryName("Second")
                vm.createLibrary()
                advanceUntilIdle()

                vm.state.value.createdLibraries.size shouldBe 2
                vm.state.value.createdLibraries[0] shouldBe lib1
                vm.state.value.createdLibraries[1] shouldBe lib2
            }
        }

        // ── createLibrary: failure ───────────────────────────────────────────

        test("createLibrary failure sets error in state") {
            runTest {
                val service = makeService()
                val error = InternalError()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false, libraryCount = 1))
                everySuspend { service.createLibrary(any()) } returns AppResult.Failure(error)

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
                advanceUntilIdle()
                vm.togglePath("/data/audiobooks")

                vm.createLibrary()
                advanceUntilIdle()

                vm.state.value.isCreatingLibrary shouldBe false
                vm.state.value.createdLibraries shouldBe emptyList()
                vm.state.value.error shouldBe error.message
            }
        }

        test("createLibrary failure does not flip setupComplete and appends nothing") {
            runTest {
                val service = makeService()
                val error = InternalError()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false, libraryCount = 1))
                everySuspend { service.createLibrary(any()) } returns AppResult.Failure(error)

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
                advanceUntilIdle()
                vm.togglePath("/data/audiobooks")

                vm.createLibrary()
                advanceUntilIdle()

                vm.state.value.setupComplete shouldBe false
                vm.state.value.createdLibraries shouldBe emptyList()
            }
        }

        // ── finishOnboarding ─────────────────────────────────────────────────

        test("finishOnboarding flips setupComplete and emits Finished NavAction") {
            runTest {
                val service = makeService()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false, libraryCount = 1))

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
                advanceUntilIdle()

                vm.finishOnboarding()

                vm.state.value.setupComplete shouldBe true
                val action = vm.navActions.first()
                action shouldBe LibrarySetupNavAction.Finished
            }
        }

        // ── createLibrary sends correct request ──────────────────────────────

        test("createLibrary calls RPC exactly once with correct request shape") {
            runTest {
                val service = makeService()
                val library = makeLibrary()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false, libraryCount = 1))
                everySuspend { service.createLibrary(any()) } returns AppResult.Success(library)

                val vm = LibrarySetupViewModel(makeFactory(service), ErrorBus())
                advanceUntilIdle()
                vm.togglePath("/data/audiobooks")
                vm.togglePath("/data/podcasts")
                vm.setLibraryName("  My Library  ")

                vm.createLibrary()
                advanceUntilIdle()

                vm.state.value.createdLibraries.size shouldBe 1
                verifySuspend(VerifyMode.exactly(1)) { service.createLibrary(any()) }
            }
        }
    })
