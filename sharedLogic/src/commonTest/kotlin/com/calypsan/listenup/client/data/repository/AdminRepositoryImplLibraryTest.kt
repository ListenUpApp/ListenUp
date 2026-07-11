package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.AccessMode
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolder
import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.DirectoryEntryResponse
import com.calypsan.listenup.client.data.remote.InviteRpcFactory
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

// ─── Fakes ──────────────────────────────────────────────────────────────────────

private fun contractLibrary(
    id: String = "lib1",
    name: String = "My Library",
    folders: List<LibraryFolderRef> = emptyList(),
) = Library(
    id = LibraryId(id),
    name = name,
    folders = folders,
    metadataPrecedence = "embedded,abs,sidecar",
    accessMode = AccessMode.SHARED,
    createdByUserId = null,
    createdAt = 1_000L,
)

/**
 * In-memory fake for [LibraryAdminService]. Records calls and serves library/folder
 * state so the repository's RPC orchestration (re-fetch after mutate, etc.) is verifiable.
 */
private class FakeLibraryAdminService : LibraryAdminService {
    val libraries = mutableMapOf<String, Library>()
    var addFolderCalls = mutableListOf<String>()
    var removedFolderIds = mutableListOf<String>()
    var scannedFolderIds = mutableListOf<String>()
    var scanFolderResult: AppResult<Unit> = AppResult.Success(Unit)
    var scanLibraryCount = 0
    var browsePaths = mutableListOf<String>()
    var browseResult: List<DirectoryEntry> = emptyList()
    private var folderSeq = 0

    fun seed(library: Library) {
        libraries[library.id.value] = library
    }

    private fun theLibrary(): Library = libraries.values.first()

    override suspend fun getLibrary(): AppResult<Library> = AppResult.Success(theLibrary())

    override suspend fun getSetupStatus(): AppResult<SetupStatus> = AppResult.Success(SetupStatus(needsSetup = libraries.isEmpty()))

    override suspend fun browseFilesystem(path: String): AppResult<List<DirectoryEntry>> {
        browsePaths += path
        return AppResult.Success(browseResult)
    }

    override suspend fun addFolder(path: String): AppResult<LibraryFolder> {
        addFolderCalls += path
        val lib = theLibrary()
        val folderId = FolderId("f${folderSeq++}")
        libraries[lib.id.value] =
            lib.copy(folders = lib.folders + LibraryFolderRef(id = folderId, rootPath = path))
        return AppResult.Success(
            LibraryFolder(id = folderId, libraryId = lib.id, rootPath = path, createdAt = 1L),
        )
    }

    override suspend fun removeFolder(folderId: FolderId): AppResult<Unit> {
        removedFolderIds += folderId.value
        return AppResult.Success(Unit)
    }

    override suspend fun scanLibrary(): AppResult<Unit> {
        scanLibraryCount++
        return AppResult.Success(Unit)
    }

    override suspend fun scanFolder(folderId: FolderId): AppResult<Unit> {
        scannedFolderIds += folderId.value
        return scanFolderResult
    }
}

private fun buildRepo(service: LibraryAdminService): AdminRepositoryImpl =
    AdminRepositoryImpl(
        adminUserChannel = RpcChannel.forTest(mock<AdminUserService>()),
        adminSettingsChannel = RpcChannel.forTest(mock<AdminSettingsService>()),
        inviteRpc = mock<InviteRpcFactory>(),
        libraryAdminChannel = RpcChannel.forTest(service),
        serverConfig = mock<ServerConfig>(),
        adminUserRosterDao = mock(),
    )

// ─── Tests ──────────────────────────────────────────────────────────────────────

class AdminRepositoryImplLibraryTest :
    FunSpec({

        test("getLibrary returns THE library mapped to domain") {
            val service = FakeLibraryAdminService()
            service.seed(
                contractLibrary(
                    id = "lib1",
                    folders =
                        listOf(
                            LibraryFolderRef(id = FolderId("f1"), rootPath = "/audiobooks"),
                            LibraryFolderRef(id = FolderId("f2"), rootPath = null),
                        ),
                ),
            )
            val repo = buildRepo(service)

            val result = repo.getLibrary()

            (result is AppResult.Success) shouldBe true
            val lib = (result as AppResult.Success).data
            lib.id shouldBe "lib1"
            lib.folders.map { it.id } shouldBe listOf("f1", "f2")
            lib.folders.map { it.rootPath } shouldBe listOf("/audiobooks", null)
        }

        test("addScanPath calls addFolder then returns the re-fetched library") {
            val service = FakeLibraryAdminService()
            service.seed(contractLibrary(id = "lib1"))
            val repo = buildRepo(service)

            val result = repo.addScanPath("/new/path")

            service.addFolderCalls shouldBe listOf("/new/path")
            (result is AppResult.Success) shouldBe true
            val lib = (result as AppResult.Success).data
            lib.folders.map { it.rootPath } shouldBe listOf("/new/path")
        }

        test("addScanPath scans ONLY the added folder, never a full library rescan") {
            val service = FakeLibraryAdminService()
            service.seed(contractLibrary(id = "lib1"))
            val repo = buildRepo(service)

            val result = repo.addScanPath("/new/path")

            (result is AppResult.Success) shouldBe true
            // The fake mints "f0" for the first added folder; the per-folder scan must target it.
            service.scannedFolderIds shouldBe listOf("f0")
            // A full rescan (minutes on a large library) must NOT be triggered.
            service.scanLibraryCount shouldBe 0
        }

        test("addScanPath still succeeds when the per-folder scan trigger fails") {
            val service = FakeLibraryAdminService()
            service.scanFolderResult =
                AppResult.Failure(
                    com.calypsan.listenup.api.error
                        .InternalError(),
                )
            service.seed(contractLibrary(id = "lib1"))
            val repo = buildRepo(service)

            val result = repo.addScanPath("/new/path")

            // The folder is already registered server-side, so a scan-trigger hiccup must not
            // fail the add — the admin is never stranded with a folder that looks like it failed.
            (result is AppResult.Success) shouldBe true
            val lib = (result as AppResult.Success).data
            lib.folders.map { it.rootPath } shouldBe listOf("/new/path")
        }

        test("triggerScan calls scanLibrary") {
            val service = FakeLibraryAdminService()
            service.seed(contractLibrary(id = "lib1"))
            val repo = buildRepo(service)

            val result = repo.triggerScan()

            service.scanLibraryCount shouldBe 1
            (result is AppResult.Success) shouldBe true
        }

        test("browseFilesystem at root maps entries with parent=null and isRoot=true") {
            val service = FakeLibraryAdminService()
            service.browseResult =
                listOf(
                    DirectoryEntry(name = "data", path = "/data", hasChildren = true, itemCount = 3),
                )
            val repo = buildRepo(service)

            val result = repo.browseFilesystem("/")

            service.browsePaths shouldBe listOf("/")
            (result is AppResult.Success) shouldBe true
            val resp = (result as AppResult.Success).data
            resp.path shouldBe "/"
            resp.parent shouldBe null
            resp.isRoot shouldBe true
            resp.entries.map { it.name } shouldBe listOf("data")
            resp.entries.map { it.path } shouldBe listOf("/data")
        }

        test("browseFilesystem at a nested path derives the parent segment and isRoot=false") {
            val service = FakeLibraryAdminService()
            service.browseResult = emptyList()
            val repo = buildRepo(service)

            val result = repo.browseFilesystem("/data/audiobooks")

            (result is AppResult.Success) shouldBe true
            val resp = (result as AppResult.Success).data
            resp.path shouldBe "/data/audiobooks"
            resp.parent shouldBe "/data"
            resp.isRoot shouldBe false
        }

        test("browseFilesystem one level below root derives parent=root") {
            val service = FakeLibraryAdminService()
            service.browseResult = emptyList()
            val repo = buildRepo(service)

            val resp = (repo.browseFilesystem("/data") as AppResult.Success).data
            resp.parent shouldBe "/"
            resp.isRoot shouldBe false
        }

        test("removeFolder calls removeFolder(folderId) then returns the re-fetched library") {
            val service = FakeLibraryAdminService()
            service.seed(
                contractLibrary(
                    id = "lib1",
                    folders = listOf(LibraryFolderRef(id = FolderId("f1"), rootPath = "/a")),
                ),
            )
            val repo = buildRepo(service)

            val result = repo.removeFolder("f1")

            service.removedFolderIds shouldBe listOf("f1")
            (result is AppResult.Success<*>) shouldBe true
        }
    })
