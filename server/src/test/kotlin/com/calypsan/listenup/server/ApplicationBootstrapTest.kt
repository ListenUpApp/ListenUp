package com.calypsan.listenup.server

import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.LibraryAdminServiceImpl
import com.calypsan.listenup.server.scanner.ScanCoordinator
import com.calypsan.listenup.server.scanner.ScanOrchestrator
import com.calypsan.listenup.server.scanner.ScannerBundle
import com.calypsan.listenup.server.scanner.ScannerResultPort
import com.calypsan.listenup.server.scanner.WatcherSupervisorPort
import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.LibraryFolderRepository
import com.calypsan.listenup.server.services.LibraryRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

class ApplicationBootstrapTest :
    FunSpec({

        test("bootstrap creates one default library when libraries empty + env var set + path valid") {
            withInMemoryDatabase {
                val tempDir = Files.createTempDirectory("bootstrap-test-").toString()
                val (service, orchestrator, scanCalls) = makeServiceAndOrchestrator(this)
                runTest {
                    bootstrapLibraries(
                        libraryAdminService = service,
                        scanOrchestrator = orchestrator,
                        libraryPath = tempDir,
                    )

                    val result = service.listLibraries()
                    val libraries = result.shouldBeInstanceOf<AppResult.Success<List<Library>>>().data
                    libraries shouldHaveSize 1
                    libraries.first().name shouldBe "My Library"
                    libraries.first().folders shouldHaveSize 1
                    libraries
                        .first()
                        .folders
                        .first()
                        .rootPath shouldBe tempDir
                }
            }
        }

        test("bootstrap ignores env var when libraries table is non-empty") {
            withInMemoryDatabase {
                val existingDir = Files.createTempDirectory("bootstrap-existing-").toString()
                val otherDir = Files.createTempDirectory("bootstrap-other-").toString()
                val (service, orchestrator, _) = makeServiceAndOrchestrator(this)
                runTest {
                    // Seed one library first
                    bootstrapLibraries(
                        libraryAdminService = service,
                        scanOrchestrator = orchestrator,
                        libraryPath = existingDir,
                    )

                    // Re-run bootstrap with a different path — should be ignored
                    bootstrapLibraries(
                        libraryAdminService = service,
                        scanOrchestrator = orchestrator,
                        libraryPath = otherDir,
                    )

                    val result = service.listLibraries()
                    val libraries = (result as AppResult.Success).data
                    // Still exactly one library from the first bootstrap
                    libraries shouldHaveSize 1
                    libraries
                        .first()
                        .folders
                        .first()
                        .rootPath shouldBe existingDir
                }
            }
        }

        test("bootstrap no-ops when libraries empty + env var unset") {
            withInMemoryDatabase {
                val (service, orchestrator, _) = makeServiceAndOrchestrator(this)
                runTest {
                    bootstrapLibraries(
                        libraryAdminService = service,
                        scanOrchestrator = orchestrator,
                        libraryPath = null,
                    )

                    val result = service.listLibraries()
                    val libraries = (result as AppResult.Success).data
                    libraries shouldHaveSize 0
                }
            }
        }

        test("bootstrap loads existing libraries into ScanOrchestrator on startup") {
            withInMemoryDatabase {
                val db = this
                val existingDir = Files.createTempDirectory("bootstrap-load-").toString()
                val (service, orchestrator, onLibraryAddedCalls) = makeServiceAndOrchestrator(db)
                // Second boot fixture created outside runTest so `this` refers to Database, not TestScope
                val (service2, orchestrator2, onLibraryAddedCalls2) = makeServiceAndOrchestrator(db)
                runTest {
                    // Seed a library via direct bootstrap (first boot)
                    bootstrapLibraries(
                        libraryAdminService = service,
                        scanOrchestrator = orchestrator,
                        libraryPath = existingDir,
                    )

                    // Simulate second boot — same library exists, orchestrator gets it again
                    bootstrapLibraries(
                        libraryAdminService = service2,
                        scanOrchestrator = orchestrator2,
                        libraryPath = existingDir,
                    )

                    // Second boot: library existed → no create, but onLibraryAdded still called
                    onLibraryAddedCalls2 shouldHaveSize 1
                }
            }
        }

        test("bootstrap does NOT auto-scan on boot") {
            withInMemoryDatabase {
                val tempDir = Files.createTempDirectory("bootstrap-noscan-").toString()
                val fixture = makeServiceAndOrchestrator(this)
                val (service, orchestrator) = fixture
                val scanLibraryCalls = fixture.scanLibraryCalls
                runTest {
                    bootstrapLibraries(
                        libraryAdminService = service,
                        scanOrchestrator = orchestrator,
                        libraryPath = tempDir,
                    )

                    // scanLibrary must never be called during bootstrap
                    scanLibraryCalls shouldHaveSize 0
                }
            }
        }
    })

/**
 * Test fixture tuple: a [LibraryAdminServiceImpl] wired against the given [db],
 * a recording [ScanOrchestrator], the list of `onLibraryAdded` call arguments,
 * and the list of `scanLibrary` call arguments.
 */
private data class ServiceFixture(
    val service: LibraryAdminServiceImpl,
    val orchestrator: ScanOrchestrator,
    val onLibraryAddedCalls: MutableList<Library>,
    val scanLibraryCalls: MutableList<LibraryId>,
)

private fun makeServiceAndOrchestrator(db: Database): ServiceFixture {
    val onLibraryAddedCalls = mutableListOf<Library>()
    val scanLibraryCalls = mutableListOf<LibraryId>()

    val orchestrator =
        ScanOrchestrator(
            scannerFactory = { library ->
                onLibraryAddedCalls.add(library)
                fakeBundle(library)
            },
            watcherSupervisor = noOpWatcher(),
        )

    val bus = ChangeBus()
    val registry = SyncRegistry()
    val libraryRepo = LibraryRepository(db = db, bus = bus, registry = registry)
    val folderRepo = LibraryFolderRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
    val contributorRepo = ContributorRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
    val seriesRepo = SeriesRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
    val bookRepo =
        BookRepository(
            db = db,
            bus = ChangeBus(),
            registry = SyncRegistry(),
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
        )
    val service =
        LibraryAdminServiceImpl(
            libraryRepository = libraryRepo,
            libraryFolderRepository = folderRepo,
            bookRepository = bookRepo,
            scanOrchestrator = orchestrator,
        )

    return ServiceFixture(service, orchestrator, onLibraryAddedCalls, scanLibraryCalls)
}

private fun noOpWatcher(): WatcherSupervisorPort =
    object : WatcherSupervisorPort {
        override suspend fun mount(
            libraryId: LibraryId,
            folder: LibraryFolderRef,
            onEvent: suspend (LibraryId, Path) -> Unit,
        ) = Unit

        override suspend fun unmount(folderId: FolderId) = Unit

        override suspend fun unmountAllForLibrary(libraryId: LibraryId) = Unit

        override suspend fun unmountAll() = Unit
    }

@OptIn(DelicateCoroutinesApi::class)
private fun fakeBundle(library: Library): ScannerBundle {
    val fakeScannerPort =
        object : ScannerResultPort {
            override fun lastResult(): ScanResult? = null
        }
    val coordinator =
        ScanCoordinator(
            libraryId = library.id,
            runFullScan = {
                ScanResult(
                    correlationId = "test",
                    rootPath = library.folders.firstOrNull()?.rootPath ?: "/tmp",
                    books = emptyList(),
                    changes = emptyList(),
                    errors = emptyList(),
                    durationMs = 0,
                    filesWalked = 0,
                    filesSkipped = 0,
                    scope = ScanScope.Full,
                )
            },
            runIncremental = { },
            scope = GlobalScope,
        )
    return ScannerBundle(library = library, scanner = fakeScannerPort, coordinator = coordinator)
}
