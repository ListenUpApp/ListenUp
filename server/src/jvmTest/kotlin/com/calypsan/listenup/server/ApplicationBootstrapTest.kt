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
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.io.files.Path
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.test.runTest

class ApplicationBootstrapTest :
    FunSpec({

        test("bootstrap ensures exactly one path-less library when no env paths") {
            withSqlDatabase {
                val (service, orchestrator, _) = makeServiceAndOrchestrator(this)
                val registry = LibraryRegistry(sql = sql)
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
            withSqlDatabase {
                val dir1 = Files.createTempDirectory("bootstrap-seed1-")
                val dir2 = Files.createTempDirectory("bootstrap-seed2-")
                val (service, orchestrator, _) = makeServiceAndOrchestrator(this)
                val registry = LibraryRegistry(sql = sql)
                runTest {
                    bootstrapLibraries(
                        libraryAdminService = service,
                        scanOrchestrator = orchestrator,
                        libraryRegistry = registry,
                        libraryPaths = listOf(Path(dir1.toString()), Path(dir2.toString())),
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
            withSqlDatabase {
                val realDir = Files.createTempDirectory("bootstrap-realdir-")
                val nonDir = Files.createTempFile("bootstrap-notadir-", ".tmp")
                val (service, orchestrator, _) = makeServiceAndOrchestrator(this)
                val registry = LibraryRegistry(sql = sql)
                runTest {
                    // addFolder rejects non-directories via LibraryError.InvalidPath
                    bootstrapLibraries(
                        libraryAdminService = service,
                        scanOrchestrator = orchestrator,
                        libraryRegistry = registry,
                        libraryPaths = listOf(Path(realDir.toString()), Path(nonDir.toString())),
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
            withSqlDatabase {
                val dir = Files.createTempDirectory("bootstrap-idempotent-")
                val registry = LibraryRegistry(sql = sql)
                val (service, orchestrator, _) = makeServiceAndOrchestrator(this)
                runTest {
                    // First boot: seeds the folder
                    bootstrapLibraries(
                        libraryAdminService = service,
                        scanOrchestrator = orchestrator,
                        libraryRegistry = registry,
                        libraryPaths = listOf(Path(dir.toString())),
                        rescanOnStartup = false,
                    )
                    // Second boot with same path — DuplicateFolder is skipped, not crash
                    val (service2, orchestrator2, _) = makeServiceAndOrchestrator(this@withSqlDatabase)
                    val registry2 = LibraryRegistry(sql = sql)
                    bootstrapLibraries(
                        libraryAdminService = service2,
                        scanOrchestrator = orchestrator2,
                        libraryRegistry = registry2,
                        libraryPaths = listOf(Path(dir.toString())),
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

private fun makeServiceAndOrchestrator(dbs: SqlTestDatabases): ServiceFixture {
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
    val libraryRepo = LibraryRepository(db = dbs.sql, bus = bus, registry = registry)
    val folderRepo =
        LibraryFolderRepository(
            db = dbs.sql,
            bus = ChangeBus(),
            registry = SyncRegistry(),
            driver = dbs.driver,
        )
    val contributorRepo = ContributorRepository(db = dbs.sql, bus = ChangeBus(), registry = SyncRegistry())
    val seriesRepo = SeriesRepository(db = dbs.sql, bus = ChangeBus(), registry = SyncRegistry())
    val bookRepo =
        BookRepository(
            db = dbs.sql,
            driver = dbs.driver,
            bus = ChangeBus(),
            registry = SyncRegistry(),
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = GenreRepository(db = dbs.sql, bus = ChangeBus(), registry = SyncRegistry()),
        )
    val service =
        LibraryAdminServiceImpl(
            libraryRepository = libraryRepo,
            libraryFolderRepository = folderRepo,
            bookRepository = bookRepo,
            scanOrchestrator = orchestrator,
            libraryRegistry =
                com.calypsan.listenup.server.services
                    .LibraryRegistry(sql = dbs.sql),
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

            override fun markSuperseded() = Unit
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
