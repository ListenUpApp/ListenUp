package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.CreateLibraryRequest
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolder
import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.LibraryError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.scanner.ScanCoordinator
import com.calypsan.listenup.server.scanner.ScanOrchestrator
import com.calypsan.listenup.server.scanner.ScannerBundle
import com.calypsan.listenup.server.scanner.ScannerResultPort
import com.calypsan.listenup.server.scanner.WatcherSupervisorPort
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.LibraryFolderRepository
import com.calypsan.listenup.server.services.LibraryRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

class LibraryAdminServiceImplTest :
    FunSpec({

        // ── Task 15: Observation methods ─────────────────────────────────────────

        test("listLibraries returns all non-tombstoned libraries with folders") {
            withInMemoryDatabase {
                val (service, libraryRepo, folderRepo) = makeService(db = this)
                runTest {
                    // Single-library invariant: only one library per server. Create it, then
                    // verify listLibraries returns it; delete and confirm it's gone.
                    val dir = createTempDir()
                    val created =
                        service.createLibrary(CreateLibraryRequest(name = "Fiction", folderPaths = listOf(dir.absolutePath)))
                    created.shouldBeInstanceOf<AppResult.Success<Library>>()

                    val result = service.listLibraries()
                    result.shouldBeInstanceOf<AppResult.Success<List<Library>>>()
                    result as AppResult.Success
                    result.data shouldHaveSize 1
                    result.data.first().name shouldBe "Fiction"

                    // Soft-delete and confirm it's filtered out.
                    service.deleteLibrary((created as AppResult.Success).data.id)
                    val afterDelete = service.listLibraries()
                    (afterDelete as AppResult.Success).data shouldHaveSize 0
                }
            }
        }

        test("getLibrary returns null for unknown id") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val result = service.getLibrary(LibraryId("no-such-id"))
                    result.shouldBeInstanceOf<AppResult.Success<Library?>>()
                    (result as AppResult.Success).data.shouldBeNull()
                }
            }
        }

        test("getLibrary returns the library with folders for a known id") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    val created = service.createLibrary(CreateLibraryRequest(name = "My Library", folderPaths = listOf(dir.absolutePath)))
                    created.shouldBeInstanceOf<AppResult.Success<Library>>()
                    val libraryId = (created as AppResult.Success).data.id

                    val result = service.getLibrary(libraryId)
                    result.shouldBeInstanceOf<AppResult.Success<Library?>>()
                    val lib = (result as AppResult.Success).data.shouldNotBeNull()
                    lib.id shouldBe libraryId
                    lib.name shouldBe "My Library"
                    lib.folders shouldHaveSize 1
                    lib.folders.first().rootPath shouldBe dir.absolutePath
                }
            }
        }

        test("getLibrary redacts folder rootPath for a member but exposes it to an admin") {
            withInMemoryDatabase {
                // Admin seeds the library; a member reads it back over the same DB.
                val (admin) = makeService(db = this, role = UserRole.ADMIN)
                val (member) = makeService(db = this, role = UserRole.MEMBER)
                runTest {
                    val dir = createTempDir()
                    val created =
                        admin.createLibrary(CreateLibraryRequest(name = "Shared", folderPaths = listOf(dir.absolutePath)))
                    val libraryId = (created as AppResult.Success).data.id

                    val adminView = (admin.getLibrary(libraryId) as AppResult.Success).data.shouldNotBeNull()
                    adminView.folders.first().rootPath shouldBe dir.absolutePath

                    val memberView = (member.getLibrary(libraryId) as AppResult.Success).data.shouldNotBeNull()
                    // Count + identity preserved, absolute path redacted.
                    memberView.folders shouldHaveSize 1
                    memberView.folders.first().id shouldBe adminView.folders.first().id
                    memberView.folders
                        .first()
                        .rootPath
                        .shouldBeNull()
                }
            }
        }

        test("listLibraries redacts every folder rootPath for a member") {
            withInMemoryDatabase {
                val (admin) = makeService(db = this, role = UserRole.ADMIN)
                val (member) = makeService(db = this, role = UserRole.MEMBER)
                runTest {
                    admin.createLibrary(CreateLibraryRequest(name = "A", folderPaths = listOf(createTempDir().absolutePath)))
                    admin.createLibrary(CreateLibraryRequest(name = "B", folderPaths = listOf(createTempDir().absolutePath)))

                    val libraries = (member.listLibraries() as AppResult.Success).data
                    libraries.flatMap { it.folders }.forEach { it.rootPath.shouldBeNull() }
                }
            }
        }

        test("createLibrary stamps createdAt from the injected clock") {
            withInMemoryDatabase {
                val fixed = 1_700_000_000_000L
                val (service) = makeService(db = this, clock = FixedClock(Instant.fromEpochMilliseconds(fixed)))
                runTest {
                    val created =
                        service.createLibrary(
                            CreateLibraryRequest(name = "Timed", folderPaths = listOf(createTempDir().absolutePath)),
                        )
                    val library = (created as AppResult.Success).data
                    library.createdAt shouldBe fixed
                }
            }
        }

        test("getSetupStatus returns needsSetup=true when libraries empty") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val result = service.getSetupStatus()
                    result.shouldBeInstanceOf<AppResult.Success<SetupStatus>>()
                    val status = (result as AppResult.Success).data
                    status.needsSetup shouldBe true
                    status.libraryCount shouldBe 0
                    (result as AppResult.Success).data.isScanning shouldBe false
                }
            }
        }

        test("getSetupStatus returns needsSetup=false + libraryCount when libraries exist") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    service.createLibrary(CreateLibraryRequest(name = "Fiction", folderPaths = listOf(dir.absolutePath)))

                    val result = service.getSetupStatus()
                    result.shouldBeInstanceOf<AppResult.Success<SetupStatus>>()
                    val status = (result as AppResult.Success).data
                    status.needsSetup shouldBe false
                    status.libraryCount shouldBe 1
                    (result as AppResult.Success).data.isScanning shouldBe false
                }
            }
        }

        test("browseFilesystem returns subdirectories for a valid path") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val parent = createTempDir()
                    parent.resolve("alpha").apply { mkdir() }
                    parent.resolve("beta").apply { mkdir() }
                    parent.resolve("file.txt").apply { createNewFile() } // not a dir

                    val result = service.browseFilesystem(parent.absolutePath)
                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                    val entries = (result as AppResult.Success).data
                    entries shouldHaveSize 2
                    entries.map { it.name }.toSet() shouldBe setOf("alpha", "beta")
                    entries.forEach { entry ->
                        entry.path.contains(parent.absolutePath) shouldBe true
                    }
                }
            }
        }

        test("browseFilesystem reports itemCount = number of immediate entries per child") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val parent = createTempDir()
                    // child "audiobooks" with 3 files + 1 subdir => itemCount 4, hasChildren true
                    val audiobooks = parent.resolve("audiobooks").apply { mkdir() }
                    audiobooks.resolve("a.m4b").apply { createNewFile() }
                    audiobooks.resolve("b.m4b").apply { createNewFile() }
                    audiobooks.resolve("c.m4b").apply { createNewFile() }
                    audiobooks.resolve("series").apply { mkdir() }
                    // child "empty" with nothing => itemCount 0, hasChildren false
                    parent.resolve("empty").apply { mkdir() }

                    val result = service.browseFilesystem(parent.absolutePath)
                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                    val entries = (result as AppResult.Success).data

                    val audio = entries.first { it.name == "audiobooks" }
                    audio.itemCount shouldBe 4
                    audio.hasChildren shouldBe true

                    val empty = entries.first { it.name == "empty" }
                    empty.itemCount shouldBe 0
                    empty.hasChildren shouldBe false
                }
            }
        }

        test("browseFilesystem returns Failure(InvalidPath) for a non-existent path") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val result = service.browseFilesystem("/no/such/path/9999999")
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    (result as AppResult.Failure).error.shouldBeInstanceOf<LibraryError.InvalidPath>()
                }
            }
        }

        // ── Task 16: Lifecycle methods ────────────────────────────────────────────

        test("createLibrary creates library + folders atomically; returns Library DTO") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir1 = createTempDir()
                    val dir2 = createTempDir()
                    val result =
                        service.createLibrary(
                            CreateLibraryRequest(name = "Fiction", folderPaths = listOf(dir1.absolutePath, dir2.absolutePath)),
                        )
                    result.shouldBeInstanceOf<AppResult.Success<Library>>()
                    val lib = (result as AppResult.Success).data
                    lib.name shouldBe "Fiction"
                    lib.folders shouldHaveSize 2
                    lib.folders.map { it.rootPath }.toSet() shouldBe setOf(dir1.absolutePath, dir2.absolutePath)
                }
            }
        }

        test("createLibrary returns Failure(InvalidPath) when a folder path does not exist") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val result =
                        service.createLibrary(
                            CreateLibraryRequest(name = "Bad", folderPaths = listOf("/no/such/path/xyz")),
                        )
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    (result as AppResult.Failure).error.shouldBeInstanceOf<LibraryError.InvalidPath>()
                }
            }
        }

        test("createLibrary second call with duplicate folder path returns the existing library (idempotent, not DuplicateFolder)") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    val first =
                        service
                            .createLibrary(CreateLibraryRequest(name = "First", folderPaths = listOf(dir.absolutePath)))
                            .shouldBeInstanceOf<AppResult.Success<Library>>()
                            .data

                    // Single-library invariant fires before duplicate-folder check:
                    // the second call returns the existing library, not DuplicateFolder.
                    val second =
                        service
                            .createLibrary(CreateLibraryRequest(name = "Duplicate", folderPaths = listOf(dir.absolutePath)))
                            .shouldBeInstanceOf<AppResult.Success<Library>>()
                            .data

                    second.id shouldBe first.id
                }
            }
        }

        test("renameLibrary updates the name; returns updated Library") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    val created = service.createLibrary(CreateLibraryRequest(name = "Old Name", folderPaths = listOf(dir.absolutePath)))
                    val id = (created as AppResult.Success).data.id

                    val result = service.renameLibrary(id, "New Name")
                    result.shouldBeInstanceOf<AppResult.Success<Library>>()
                    (result as AppResult.Success).data.name shouldBe "New Name"
                }
            }
        }

        test("renameLibrary returns Failure(NotFound) for unknown id") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val result = service.renameLibrary(LibraryId("no-such"), "X")
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    (result as AppResult.Failure).error.shouldBeInstanceOf<LibraryError.NotFound>()
                }
            }
        }

        test("deleteLibrary cascade-soft-deletes folders + library") {
            withInMemoryDatabase {
                val (service, libraryRepo, folderRepo) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    val created = service.createLibrary(CreateLibraryRequest(name = "ToDelete", folderPaths = listOf(dir.absolutePath)))
                    val libId = (created as AppResult.Success).data.id

                    val deleteResult = service.deleteLibrary(libId)
                    deleteResult.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Library should be tombstoned
                    val libPage = libraryRepo.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
                    val lib = libPage.items.firstOrNull { it.id == libId.value }
                    lib.shouldNotBeNull()
                    lib.deletedAt.shouldNotBeNull()

                    // Folders should be tombstoned
                    val folderPage = folderRepo.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
                    val folders = folderPage.items.filter { it.libraryId == libId.value }
                    folders.forEach { it.deletedAt.shouldNotBeNull() }
                }
            }
        }

        test("deleteLibrary is idempotent — second call returns Success") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    val created = service.createLibrary(CreateLibraryRequest(name = "Library", folderPaths = listOf(dir.absolutePath)))
                    val libId = (created as AppResult.Success).data.id

                    service.deleteLibrary(libId)
                    val second = service.deleteLibrary(libId)
                    second.shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }

        test("addFolder creates new folder under library") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir1 = createTempDir()
                    val dir2 = createTempDir()
                    val created = service.createLibrary(CreateLibraryRequest(name = "Lib", folderPaths = listOf(dir1.absolutePath)))
                    val libId = (created as AppResult.Success).data.id

                    val result = service.addFolder(libId, dir2.absolutePath)
                    result.shouldBeInstanceOf<AppResult.Success<LibraryFolder>>()
                    val folder = (result as AppResult.Success).data
                    folder.rootPath shouldBe dir2.absolutePath
                    folder.libraryId shouldBe libId
                }
            }
        }

        test("addFolder returns Failure(NotFound) for unknown libraryId") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    val result = service.addFolder(LibraryId("no-such"), dir.absolutePath)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    (result as AppResult.Failure).error.shouldBeInstanceOf<LibraryError.NotFound>()
                }
            }
        }

        test("addFolder returns Failure(DuplicateFolder) when path already registered") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    val created = service.createLibrary(CreateLibraryRequest(name = "Lib", folderPaths = listOf(dir.absolutePath)))
                    val libId = (created as AppResult.Success).data.id

                    val result = service.addFolder(libId, dir.absolutePath)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    (result as AppResult.Failure).error.shouldBeInstanceOf<LibraryError.DuplicateFolder>()
                }
            }
        }

        test("addFolder returns Failure(InvalidPath) when path does not exist") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    val created = service.createLibrary(CreateLibraryRequest(name = "Lib", folderPaths = listOf(dir.absolutePath)))
                    val libId = (created as AppResult.Success).data.id

                    val result = service.addFolder(libId, "/no/such/path/xyz")
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    (result as AppResult.Failure).error.shouldBeInstanceOf<LibraryError.InvalidPath>()
                }
            }
        }

        test("addFolder can add multiple folders to the same library") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir1 = createTempDir()
                    val dir2 = createTempDir()
                    val dir3 = createTempDir()
                    val created = service.createLibrary(CreateLibraryRequest(name = "Lib", folderPaths = listOf(dir1.absolutePath)))
                    val libId = (created as AppResult.Success).data.id

                    service.addFolder(libId, dir2.absolutePath)
                    service.addFolder(libId, dir3.absolutePath)

                    val lib = service.getLibrary(libId)
                    (lib as AppResult.Success).data!!.folders shouldHaveSize 3
                }
            }
        }

        test("removeFolder cascade-soft-deletes folder") {
            withInMemoryDatabase {
                val (service, _, folderRepo) = makeService(db = this)
                runTest {
                    val dir1 = createTempDir()
                    val dir2 = createTempDir()
                    val created =
                        service.createLibrary(
                            CreateLibraryRequest(name = "Lib", folderPaths = listOf(dir1.absolutePath, dir2.absolutePath)),
                        )
                    val folders = (created as AppResult.Success).data.folders
                    val folderToRemove = folders.first { it.rootPath == dir2.absolutePath }

                    val result = service.removeFolder(folderToRemove.id)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val folderPage = folderRepo.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
                    folderPage.items
                        .first { it.id == folderToRemove.id.value }
                        .deletedAt
                        .shouldNotBeNull()
                }
            }
        }

        test("removeFolder returns Failure(FolderNotFound) for unknown folder") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val result = service.removeFolder(FolderId("no-such"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    (result as AppResult.Failure).error.shouldBeInstanceOf<LibraryError.FolderNotFound>()
                }
            }
        }

        // ── Task 17: Scan triggers ────────────────────────────────────────────────

        test("scanLibrary delegates to scanOrchestrator; returns Success") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    val created = service.createLibrary(CreateLibraryRequest(name = "Lib", folderPaths = listOf(dir.absolutePath)))
                    val libId = (created as AppResult.Success).data.id

                    val result = service.scanLibrary(libId)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }

        test("scanLibrary returns Failure(NotFound) when library not registered in orchestrator") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    // No library registered, orchestrator returns NotFound
                    val result = service.scanLibrary(LibraryId("no-such"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    (result as AppResult.Failure).error.shouldBeInstanceOf<LibraryError.NotFound>()
                }
            }
        }

        test("scanFolder returns Success when folder exists") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    val created = service.createLibrary(CreateLibraryRequest(name = "Lib", folderPaths = listOf(dir.absolutePath)))
                    val folderId =
                        (created as AppResult.Success)
                            .data.folders
                            .first()
                            .id

                    val result = service.scanFolder(folderId)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }

        test("scanFolder returns Failure(FolderNotFound) when folder not registered") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val result = service.scanFolder(FolderId("no-such"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    (result as AppResult.Failure).error.shouldBeInstanceOf<LibraryError.FolderNotFound>()
                }
            }
        }

        // ── Single-library invariant ──────────────────────────────────────────────

        test("createLibrary is idempotent — a second call returns the existing library, no second row") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val tempDir = createTempDir()
                    val first =
                        service
                            .createLibrary(CreateLibraryRequest(name = "Books", folderPaths = listOf(tempDir.absolutePath)))
                            .shouldBeInstanceOf<AppResult.Success<Library>>()
                            .data

                    val second =
                        service
                            .createLibrary(CreateLibraryRequest(name = "Other", folderPaths = listOf(tempDir.absolutePath)))
                            .shouldBeInstanceOf<AppResult.Success<Library>>()
                            .data

                    second.id shouldBe first.id
                    service
                        .listLibraries()
                        .shouldBeInstanceOf<AppResult.Success<List<Library>>>()
                        .data
                        .size shouldBe 1
                }
            }
        }

        // ── Multi-user: admin-gated structural ops ────────────────────────────────

        test("createLibrary by a MEMBER is denied with PermissionDenied") {
            withInMemoryDatabase {
                val (service) = makeService(db = this, role = UserRole.MEMBER)
                runTest {
                    val dir = createTempDir()
                    service
                        .createLibrary(CreateLibraryRequest(name = "Nope", folderPaths = listOf(dir.absolutePath)))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("deleteLibrary by a MEMBER is denied with PermissionDenied") {
            withInMemoryDatabase {
                val (memberService) = makeService(db = this, role = UserRole.MEMBER)
                val (adminService) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    val created =
                        adminService.createLibrary(CreateLibraryRequest(name = "Lib", folderPaths = listOf(dir.absolutePath)))
                    val libId = (created as AppResult.Success).data.id

                    memberService
                        .deleteLibrary(libId)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("browseFilesystem by a MEMBER is denied with PermissionDenied") {
            withInMemoryDatabase {
                val (service) = makeService(db = this, role = UserRole.MEMBER)
                runTest {
                    val dir = createTempDir()
                    service
                        .browseFilesystem(dir.absolutePath)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("listLibraries by a MEMBER is allowed (member library browsing stays open)") {
            withInMemoryDatabase {
                val (memberService) = makeService(db = this, role = UserRole.MEMBER)
                val (adminService) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    adminService.createLibrary(CreateLibraryRequest(name = "Fiction", folderPaths = listOf(dir.absolutePath)))

                    val result = memberService.listLibraries()
                    result.shouldBeInstanceOf<AppResult.Success<List<Library>>>()
                    (result as AppResult.Success).data shouldHaveSize 1
                }
            }
        }

        test("createLibrary by an ADMIN succeeds") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    service
                        .createLibrary(CreateLibraryRequest(name = "Fiction", folderPaths = listOf(dir.absolutePath)))
                        .shouldBeInstanceOf<AppResult.Success<Library>>()
                }
            }
        }

        // ── Inbox toggle ──────────────────────────────────────────────────────────

        test("setInboxEnabled flips the flag for an admin and returns the updated Library") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    val created =
                        service.createLibrary(CreateLibraryRequest(name = "Lib", folderPaths = listOf(dir.absolutePath)))
                    val libraryId = (created as AppResult.Success).data.id
                    created.data.inboxEnabled shouldBe false

                    val result = service.setInboxEnabled(libraryId, true)
                    result.shouldBeInstanceOf<AppResult.Success<Library>>()
                    (result as AppResult.Success).data.inboxEnabled shouldBe true

                    // Persisted: a fresh read reflects the flag.
                    val reloaded = (service.getLibrary(libraryId) as AppResult.Success).data.shouldNotBeNull()
                    reloaded.inboxEnabled shouldBe true

                    // Toggle back off.
                    val offResult = service.setInboxEnabled(libraryId, false)
                    (offResult as AppResult.Success).data.inboxEnabled shouldBe false
                }
            }
        }

        test("setInboxEnabled returns Failure(NotFound) for unknown id") {
            withInMemoryDatabase {
                val (service) = makeService(db = this)
                runTest {
                    val result = service.setInboxEnabled(LibraryId("no-such"), true)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    (result as AppResult.Failure).error.shouldBeInstanceOf<LibraryError.NotFound>()
                }
            }
        }

        test("setInboxEnabled by a MEMBER is denied with PermissionDenied") {
            withInMemoryDatabase {
                val (memberService) = makeService(db = this, role = UserRole.MEMBER)
                val (adminService) = makeService(db = this)
                runTest {
                    val dir = createTempDir()
                    val created =
                        adminService.createLibrary(CreateLibraryRequest(name = "Lib", folderPaths = listOf(dir.absolutePath)))
                    val libraryId = (created as AppResult.Success).data.id

                    memberService
                        .setInboxEnabled(libraryId, true)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }
    })

// ── Test helpers ──────────────────────────────────────────────────────────────

private data class ServiceFixture(
    val service: LibraryAdminServiceImpl,
    val libraryRepo: LibraryRepository,
    val folderRepo: LibraryFolderRepository,
)

private fun makeService(
    db: Database,
    orchestrator: ScanOrchestrator = noOpOrchestrator(db),
    role: UserRole = UserRole.ADMIN,
    clock: Clock = Clock.System,
): ServiceFixture {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val libraryRepo = LibraryRepository(db = db, bus = bus, registry = registry)
    val folderRepo = LibraryFolderRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
    val contributorRepo =
        com.calypsan.listenup.server.services.ContributorRepository(
            db = db,
            bus = ChangeBus(),
            registry = SyncRegistry(),
        )
    val seriesRepo =
        com.calypsan.listenup.server.services.SeriesRepository(
            db = db,
            bus = ChangeBus(),
            registry = SyncRegistry(),
        )
    val bookRepo =
        BookRepository(
            db = db,
            bus = ChangeBus(),
            registry = SyncRegistry(),
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository =
                com.calypsan.listenup.server.services.GenreRepository(
                    db = db,
                    bus = ChangeBus(),
                    registry = SyncRegistry(),
                ),
        )
    val service =
        LibraryAdminServiceImpl(
            libraryRepository = libraryRepo,
            libraryFolderRepository = folderRepo,
            bookRepository = bookRepo,
            scanOrchestrator = orchestrator,
            clock = clock,
        ).copyWith(
            PrincipalProvider { UserPrincipal(UserId("caller"), SessionId("s-caller"), role) },
        )
    return ServiceFixture(service, libraryRepo, folderRepo)
}

private fun fakeWatcher(): WatcherSupervisorPort =
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

private fun fakeBundle(
    library: Library,
    scope: CoroutineScope,
): ScannerBundle {
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
            scope = scope,
        )
    return ScannerBundle(library = library, scanner = fakeScannerPort, coordinator = coordinator)
}

/** A no-op [ScanOrchestrator] for tests that don't care about orchestrator interactions. */
private fun noOpOrchestrator(
    @Suppress("UNUSED_PARAMETER") db: Database,
): ScanOrchestrator =
    ScanOrchestrator(
        scannerFactory = { library -> fakeBundle(library, kotlinx.coroutines.GlobalScope) },
        watcherSupervisor = fakeWatcher(),
    )

private fun createTempDir(): java.io.File = Files.createTempDirectory("listenup-test-").toFile().apply { deleteOnExit() }
