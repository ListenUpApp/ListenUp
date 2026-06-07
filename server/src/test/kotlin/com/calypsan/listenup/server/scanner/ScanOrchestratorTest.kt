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

        test("onLibraryRemoved tears down scanner and unmounts all watchers") {
            runTest {
                val factory = FakeScannerFactory()
                val supervisor = FakeWatcherSupervisor()
                val orchestrator = orchestrator(factory, supervisor, backgroundScope)

                val library = testLib("lib-1", "/tmp/books")
                orchestrator.onLibraryAdded(library)
                orchestrator.onLibraryRemoved(LibraryId("lib-1"))

                supervisor.unmountAllLibraryCalls shouldBe 1
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

        test("concurrent scanLibrary on different libraries both proceed") {
            runTest {
                val gate1 = CompletableDeferred<Unit>()
                val gate2 = CompletableDeferred<Unit>()
                val factory = FakeScannerFactory(gate1, gate2)
                val orchestrator = orchestrator(factory, FakeWatcherSupervisor(), backgroundScope)

                orchestrator.onLibraryAdded(testLib("lib-1", "/tmp/a"))
                orchestrator.onLibraryAdded(testLib("lib-2", "/tmp/b"))

                val job1 = async { orchestrator.scanLibrary(LibraryId("lib-1")) }
                val job2 = async { orchestrator.scanLibrary(LibraryId("lib-2")) }

                gate1.complete(Unit)
                gate2.complete(Unit)
                job1.await().shouldBeInstanceOf<AppResult.Success<ScanResult>>()
                job2.await().shouldBeInstanceOf<AppResult.Success<ScanResult>>()
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
 * Factory that produces [ScannerBundle]s with an optional gate to block full scans.
 * The first gate controls library-1's scanner; the second controls library-2's.
 */
private class FakeScannerFactory(
    vararg gates: CompletableDeferred<Unit>,
) {
    private val gateQueue = ArrayDeque(gates.toList())
    var scannersCreated = 0

    fun create(
        library: Library,
        scope: kotlinx.coroutines.CoroutineScope,
    ): ScannerBundle {
        scannersCreated++
        val gate = if (gateQueue.isNotEmpty()) gateQueue.removeFirst() else null
        val scanner = FakeScanner()
        val coordinator =
            ScanCoordinator(
                libraryId = library.id,
                runFullScan = {
                    gate?.await()
                    emptyResult()
                },
                runIncremental = { /* no-op */ },
                scope = scope,
            )
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
    var unmountAllCalls = 0

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

    override suspend fun unmountAll() {
        unmountAllCalls++
    }
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
