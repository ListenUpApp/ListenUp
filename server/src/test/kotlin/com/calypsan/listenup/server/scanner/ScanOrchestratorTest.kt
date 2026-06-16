@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.AccessMode
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.error.LibraryError
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.nio.file.Path

/**
 * Unit tests for [ScanOrchestrator].
 *
 * Uses hand-rolled fakes for [Scanner] and [WatcherSupervisor] — both
 * are concrete classes with real dependencies that are not easily instantiated
 * in unit-test context.
 */
class ScanOrchestratorTest :
    FunSpec({

        test("onLibraryAdded creates a Scanner and mounts watchers") {
            runTest {
                val factory = FakeScannerFactory()
                val supervisor = FakeWatcherSupervisor()
                val orchestrator = orchestrator(factory, supervisor, backgroundScope)

                val library = testLib("lib-1", "/tmp/books")
                orchestrator.onLibraryAdded(library)

                factory.scannersCreated shouldBe 1
                supervisor.mountedFolders.size shouldBe 1
            }
        }

        test("onFolderAdded mounts a new watcher for that folder") {
            runTest {
                val supervisor = FakeWatcherSupervisor()
                val orchestrator = orchestrator(FakeScannerFactory(), supervisor, backgroundScope)

                val library = testLib("lib-1", "/tmp/books")
                orchestrator.onLibraryAdded(library)
                val newFolder = LibraryFolderRef(FolderId("f-extra"), "/tmp/extras")
                orchestrator.onFolderAdded(LibraryId("lib-1"), newFolder)

                supervisor.mountedFolders.size shouldBe 2
            }
        }

        test("onFolderRemoved unmounts the watcher for that folder") {
            runTest {
                val supervisor = FakeWatcherSupervisor()
                val orchestrator = orchestrator(FakeScannerFactory(), supervisor, backgroundScope)

                val library = testLib("lib-1", "/tmp/books")
                orchestrator.onLibraryAdded(library)
                orchestrator.onFolderRemoved(FolderId("lib-1-f-0"))

                supervisor.unmountedFolders.size shouldBe 1
            }
        }

        test("scanLibrary delegates to the coordinator and returns Success") {
            runTest {
                val orchestrator = orchestrator(FakeScannerFactory(), FakeWatcherSupervisor(), backgroundScope)
                orchestrator.onLibraryAdded(testLib("lib-1", "/tmp/books"))

                val result = orchestrator.scanLibrary(LibraryId("lib-1"))
                result.shouldBeInstanceOf<AppResult.Success<ScanResult>>()
            }
        }

        test("scanLibraryAsync returns Success immediately for a registered library") {
            runTest {
                val orchestrator = orchestrator(FakeScannerFactory(), FakeWatcherSupervisor(), backgroundScope)
                orchestrator.onLibraryAdded(testLib("lib-1", "/tmp/books"))

                val result = orchestrator.scanLibraryAsync(LibraryId("lib-1"))
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("scanLibraryAsync returns LibraryError.NotFound for unknown library") {
            runTest {
                val orchestrator = orchestrator(FakeScannerFactory(), FakeWatcherSupervisor(), backgroundScope)

                val result = orchestrator.scanLibraryAsync(LibraryId("ghost"))
                result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<LibraryError.NotFound>()
            }
        }

        test("scanLibrary returns LibraryError.NotFound for unknown library") {
            runTest {
                val orchestrator = orchestrator(FakeScannerFactory(), FakeWatcherSupervisor(), backgroundScope)

                val result = orchestrator.scanLibrary(LibraryId("ghost"))
                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<LibraryError.NotFound>()
            }
        }

        test("onLibraryAdded replaces the single bundle; only the latest library is scannable") {
            runTest {
                val orchestrator = orchestrator(FakeScannerFactory(), FakeWatcherSupervisor(), backgroundScope)
                val libraryA = testLib("lib-a", "/tmp/a")
                val libraryB = testLib("lib-b", "/tmp/b")

                orchestrator.onLibraryAdded(libraryA)
                orchestrator.onLibraryAdded(libraryB)

                orchestrator.registeredLibraryIds() shouldBe listOf(libraryB.id)
                (orchestrator.scanLibrary(libraryA.id) as AppResult.Failure).error.shouldBeInstanceOf<LibraryError.NotFound>()
                orchestrator.scanLibrary(libraryB.id).shouldBeInstanceOf<AppResult.Success<ScanResult>>()
            }
        }

        test("isScanning is false when no scan is running") {
            runTest {
                val orchestrator = orchestrator(FakeScannerFactory(), FakeWatcherSupervisor(), backgroundScope)
                orchestrator.onLibraryAdded(testLib("lib-1", "/tmp/books"))
                orchestrator.isScanning() shouldBe false
            }
        }

        test("onFolderAdded rebuilds the scanner bundle so the new folder is in the Scanner's roots") {
            runTest {
                val factory = FakeScannerFactory()
                val orchestrator = orchestrator(factory, FakeWatcherSupervisor(), backgroundScope)

                val library = testLib("lib-1", "/tmp/books")
                orchestrator.onLibraryAdded(library)
                factory.scannersCreated shouldBe 1

                val newFolder = LibraryFolderRef(FolderId("f-extra"), "/tmp/extras")
                orchestrator.onFolderAdded(LibraryId("lib-1"), newFolder)

                // Factory must have been invoked a second time with the updated folder list.
                factory.scannersCreated shouldBe 2
                val rebuildLibrary = factory.librariesUsedForCreation.last()
                rebuildLibrary.folders.map { it.id } shouldBe
                    listOf(FolderId("lib-1-f-0"), FolderId("f-extra"))
            }
        }

        test("onFolderRemoved rebuilds the scanner bundle so the removed folder is gone") {
            runTest {
                val factory = FakeScannerFactory()
                val orchestrator = orchestrator(factory, FakeWatcherSupervisor(), backgroundScope)

                val library = testLib("lib-1", "/tmp/books")
                orchestrator.onLibraryAdded(library)
                factory.scannersCreated shouldBe 1

                orchestrator.onFolderRemoved(FolderId("lib-1-f-0"))

                // Factory must have been invoked a second time with the folder removed.
                factory.scannersCreated shouldBe 2
                val rebuildLibrary = factory.librariesUsedForCreation.last()
                rebuildLibrary.folders shouldBe emptyList()
            }
        }

        test("onFolderAdded closes the old coordinator to prevent coroutine leak") {
            runTest {
                val factory = FakeScannerFactory()
                val orchestrator = orchestrator(factory, FakeWatcherSupervisor(), backgroundScope)

                val library = testLib("lib-1", "/tmp/books")
                orchestrator.onLibraryAdded(library)

                // Capture a reference to the first coordinator before the bundle is replaced.
                val firstCoordinator = factory.lastCoordinator!!

                val newFolder = LibraryFolderRef(FolderId("f-extra"), "/tmp/extras")
                orchestrator.onFolderAdded(LibraryId("lib-1"), newFolder)

                // The old coordinator's channel must be closed so its worker loop can exit.
                firstCoordinator.isChannelClosed() shouldBe true
            }
        }

        test("onFolderRemoved closes the old coordinator to prevent coroutine leak") {
            runTest {
                val factory = FakeScannerFactory()
                val orchestrator = orchestrator(factory, FakeWatcherSupervisor(), backgroundScope)

                val library = testLib("lib-1", "/tmp/books")
                orchestrator.onLibraryAdded(library)

                val firstCoordinator = factory.lastCoordinator!!

                orchestrator.onFolderRemoved(FolderId("lib-1-f-0"))

                firstCoordinator.isChannelClosed() shouldBe true
            }
        }

        test("scanFolder finds new folder after onFolderAdded updates the snapshot") {
            runTest {
                val incrementalPaths = mutableListOf<java.nio.file.Path>()
                val factory = FakeScannerFactory(recordIncremental = { path -> incrementalPaths.add(path) })
                val orchestrator = orchestrator(factory, FakeWatcherSupervisor(), backgroundScope)

                // Library starts with no folders; only add the folder via onFolderAdded.
                val library =
                    Library(
                        id = LibraryId("lib-1"),
                        name = "Test Library",
                        folders = emptyList(),
                        metadataPrecedence = "embedded,abs,sidecar",
                        accessMode = AccessMode.SHARED,
                        createdByUserId = null,
                        createdAt = 0L,
                    )
                orchestrator.onLibraryAdded(library)

                val newFolder = LibraryFolderRef(FolderId("f-new"), "/tmp/extras")
                orchestrator.onFolderAdded(LibraryId("lib-1"), newFolder)

                // scanFolder must find the new folder in the snapshot — before the fix it no-ops.
                orchestrator.scanFolder(FolderId("f-new"))
                // Drain the incremental channel so the runIncremental lambda fires.
                testScheduler.runCurrent()

                incrementalPaths.map { it.toString() } shouldBe listOf("/tmp/extras")
            }
        }

        test("concurrent scanLibrary on the same library collapses via single-flight") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val factory = FakeScannerFactory(gate)
                val orchestrator = orchestrator(factory, FakeWatcherSupervisor(), backgroundScope)
                orchestrator.onLibraryAdded(testLib("lib-1", "/tmp/books"))

                val job1 = async { orchestrator.scanLibrary(LibraryId("lib-1")) }
                // Let the first scan acquire the mutex before launching the second.
                testScheduler.runCurrent()

                val job2 = orchestrator.scanLibrary(LibraryId("lib-1"))
                // Second call hits AlreadyRunning while first is in flight.
                val failure = job2.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<ScanError.AlreadyRunning>()

                gate.complete(Unit)
                job1.await().shouldBeInstanceOf<AppResult.Success<ScanResult>>()
            }
        }
    })

// --- Helpers ----------------------------------------------------------------

private fun testLib(
    id: String,
    folderPath: String,
): Library =
    Library(
        id = LibraryId(id),
        name = "Test Library $id",
        folders = listOf(LibraryFolderRef(FolderId("$id-f-0"), folderPath)),
        metadataPrecedence = "embedded,abs,sidecar",
        accessMode = AccessMode.SHARED,
        createdByUserId = null,
        createdAt = 0L,
    )

private fun orchestrator(
    factory: FakeScannerFactory,
    supervisor: FakeWatcherSupervisor,
    scope: kotlinx.coroutines.CoroutineScope,
): ScanOrchestrator =
    ScanOrchestrator(
        scannerFactory = { lib -> factory.create(lib, scope) },
        watcherSupervisor = supervisor,
    )

// --- Fakes ------------------------------------------------------------------

/**
 * Factory that produces [ScannerBundle]s with an optional gate to block full scans
 * and an optional recorder for incremental re-analysis paths.
 * The first gate controls library-1's scanner; the second controls library-2's.
 */
private class FakeScannerFactory(
    vararg gates: CompletableDeferred<Unit>,
    private val recordIncremental: (suspend (java.nio.file.Path) -> Unit)? = null,
) {
    private val gateQueue = ArrayDeque(gates.toList())
    var scannersCreated = 0
    val librariesUsedForCreation = mutableListOf<Library>()

    /** The most recently created [ScanCoordinator]. Useful for checking teardown. */
    var lastCoordinator: ScanCoordinator? = null

    fun create(
        library: Library,
        scope: kotlinx.coroutines.CoroutineScope,
    ): ScannerBundle {
        scannersCreated++
        librariesUsedForCreation += library
        val gate = if (gateQueue.isNotEmpty()) gateQueue.removeFirst() else null
        val scanner = FakeScanner()
        val coordinator =
            ScanCoordinator(
                libraryId = library.id,
                runFullScan = {
                    gate?.await()
                    emptyResult()
                },
                runIncremental = { path -> recordIncremental?.invoke(path) },
                scope = scope,
            )
        lastCoordinator = coordinator
        return ScannerBundle(library, scanner, coordinator)
    }
}

/** Minimal [ScannerResultPort] fake — always returns null for lastResult. */
private class FakeScanner : ScannerResultPort {
    override fun lastResult(): ScanResult? = null
}

private class FakeWatcherSupervisor : WatcherSupervisorPort {
    val mountedFolders = mutableListOf<LibraryFolderRef>()
    val unmountedFolders = mutableListOf<FolderId>()
    var unmountAllLibraryCalls = 0

    override suspend fun mount(
        libraryId: LibraryId,
        folder: LibraryFolderRef,
        onEvent: suspend (LibraryId, Path) -> Unit,
    ) {
        mountedFolders += folder
    }

    override suspend fun unmount(folderId: FolderId) {
        unmountedFolders += folderId
    }

    override suspend fun unmountAllForLibrary(libraryId: LibraryId) {
        unmountAllLibraryCalls++
    }

    override suspend fun unmountAll() = Unit
}

private fun emptyResult(): ScanResult =
    ScanResult(
        correlationId = "test",
        rootPath = "/library",
        books = emptyList(),
        changes = emptyList(),
        errors = emptyList(),
        durationMs = 0,
        filesWalked = 0,
        filesSkipped = 0,
        scope = ScanScope.Full,
    )
