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
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.LibraryFolderRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.LibraryRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.asSqlDriver

class ApplicationBootstrapTest :
    FunSpec({

        test("bootstrap ensures exactly one path-less library when no env paths") {
            withInMemoryDatabase {
                val db = this
                val (service, orchestrator, _) = makeServiceAndOrchestrator(db)
                val registry = LibraryRegistry(db = db)
                runTest {
                    bootstrapLibraries(
                        libraryAdminService = service,
                        scanOrchestrator = orchestrator,
                        libraryRegistry = registry,
                        libraryPaths = emptyList(),
                        rescanOnStartup = false,
                    )

                    val result = service.getLibrary()
                    val library = result.shouldBeInstanceOf<AppResult.Success<Library>>().data
                    library.folders.shouldBeEmpty()
                }
            }
        }

        test("bootstrap seeds each env path as a folder of the singleton") {
            withInMemoryDatabase {
                val db = this
                val dir1 = Files.createTempDirectory("bootstrap-seed1-")
                val dir2 = Files.createTempDirectory("bootstrap-seed2-")
                val (service, orchestrator, _) = makeServiceAndOrchestrator(db)
                val registry = LibraryRegistry(db = db)
                runTest {
                    bootstrapLibraries(
                        libraryAdminService = service,
                        scanOrchestrator = orchestrator,
                        libraryRegistry = registry,
                        libraryPaths = listOf(dir1, dir2),
                        rescanOnStartup = false,
                    )

                    val result = service.getLibrary()
                    val library = result.shouldBeInstanceOf<AppResult.Success<Library>>().data
                    library.folders shouldHaveSize 2
                    val rootPaths = library.folders.map { it.rootPath }.toSet()
                    rootPaths shouldBe setOf(dir1.toString(), dir2.toString())
                }
            }
        }

        test("bootstrap skips a non-directory path") {
            withInMemoryDatabase {
                val db = this
                val realDir = Files.createTempDirectory("bootstrap-realdir-")
                val nonDir = Files.createTempFile("bootstrap-notadir-", ".tmp")
                val (service, orchestrator, _) = makeServiceAndOrchestrator(db)
                val registry = LibraryRegistry(db = db)
                runTest {
                    // addFolder rejects non-directories via LibraryError.InvalidPath
                    bootstrapLibraries(
                        libraryAdminService = service,
                        scanOrchestrator = orchestrator,
                        libraryRegistry = registry,
                        libraryPaths = listOf(realDir, nonDir),
                        rescanOnStartup = false,
                    )

                    val result = service.getLibrary()
                    val library = result.shouldBeInstanceOf<AppResult.Success<Library>>().data
                    // Only the real directory was seeded; the temp file was skipped
                    library.folders shouldHaveSize 1
                    library.folders.first().rootPath shouldBe realDir.toString()
                }
            }
        }

        test("bootstrap does not re-seed folders on second boot (idempotent)") {
            withInMemoryDatabase {
                val db = this
                val dir = Files.createTempDirectory("bootstrap-idempotent-")
                val registry = LibraryRegistry(db = db)
                val (service, orchestrator, _) = makeServiceAndOrchestrator(db)
                runTest {
                    // First boot: seeds the folder
                    bootstrapLibraries(
                        libraryAdminService = service,
                        scanOrchestrator = orchestrator,
                        libraryRegistry = registry,
                        libraryPaths = listOf(dir),
                        rescanOnStartup = false,
                    )
                    // Second boot with same path — DuplicateFolder is skipped, not crash
                    val (service2, orchestrator2, _) = makeServiceAndOrchestrator(db)
                    val registry2 = LibraryRegistry(db = db)
                    bootstrapLibraries(
                        libraryAdminService = service2,
                        scanOrchestrator = orchestrator2,
                        libraryRegistry = registry2,
                        libraryPaths = listOf(dir),
                        rescanOnStartup = false,
                    )

                    val result = service2.getLibrary()
                    val library = result.shouldBeInstanceOf<AppResult.Success<Library>>().data
                    // Still exactly one folder — no duplicates
                    library.folders shouldHaveSize 1
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
    val libraryRepo = LibraryRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
    val folderRepo =
        LibraryFolderRepository(
            db = db.asSqlDatabase(),
            bus = ChangeBus(),
            registry = SyncRegistry(),
            driver = db.asSqlDriver(),
        )
    val contributorRepo = ContributorRepository(db = db.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
    val seriesRepo = SeriesRepository(db = db.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
    val bookRepo =
        BookRepository(
            db = db.asSqlDatabase(),
            driver = db.asSqlDriver(),
            exposedDb = db,
            bus = ChangeBus(),
            registry = SyncRegistry(),
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = GenreRepository(db = db.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry()),
        )
    val service =
        LibraryAdminServiceImpl(
            libraryRepository = libraryRepo,
            libraryFolderRepository = folderRepo,
            bookRepository = bookRepo,
            scanOrchestrator = orchestrator,
            libraryRegistry =
                com.calypsan.listenup.server.services
                    .LibraryRegistry(db = db),
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
