package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.AccessMode
import com.calypsan.listenup.api.dto.CreateLibraryRequest
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolder
import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.AdminApiContract
import com.calypsan.listenup.client.data.remote.AdminSettingsRpcFactory
import com.calypsan.listenup.client.data.remote.AdminUserRpcFactory
import com.calypsan.listenup.client.data.remote.InviteRpcFactory
import com.calypsan.listenup.client.data.remote.LibraryAdminRpcFactory
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
    inboxEnabled: Boolean = false,
) = Library(
    id = LibraryId(id),
    name = name,
    folders = folders,
    metadataPrecedence = "embedded,abs,sidecar",
    accessMode = AccessMode.SHARED,
    createdByUserId = null,
    createdAt = 1_000L,
    inboxEnabled = inboxEnabled,
)

/**
 * In-memory fake for [LibraryAdminService]. Records calls and serves library/folder
 * state so the repository's RPC orchestration (re-fetch after mutate, etc.) is verifiable.
 */
private class FakeLibraryAdminService : LibraryAdminService {
    val libraries = mutableMapOf<String, Library>()
    var addFolderCalls = mutableListOf<Pair<String, String>>()
    var removedFolderIds = mutableListOf<String>()
    var scannedLibraryIds = mutableListOf<String>()
    var browsePaths = mutableListOf<String>()
    var browseResult: List<DirectoryEntry> = emptyList()
    private var folderSeq = 0

    fun seed(library: Library) {
        libraries[library.id.value] = library
    }

    override suspend fun listLibraries(): AppResult<List<Library>> = AppResult.Success(libraries.values.toList())

    override suspend fun getLibrary(id: LibraryId): AppResult<Library?> = AppResult.Success(libraries[id.value])

    override suspend fun getSetupStatus(): AppResult<SetupStatus> = AppResult.Success(SetupStatus(needsSetup = libraries.isEmpty(), libraryCount = libraries.size))

    override suspend fun browseFilesystem(path: String): AppResult<List<DirectoryEntry>> {
        browsePaths += path
        return AppResult.Success(browseResult)
    }

    override suspend fun createLibrary(request: CreateLibraryRequest): AppResult<Library> {
        val lib = contractLibrary(id = "new", name = request.name)
        libraries[lib.id.value] = lib
        return AppResult.Success(lib)
    }

    override suspend fun renameLibrary(
        id: LibraryId,
        name: String,
    ): AppResult<Library> {
        val updated = libraries.getValue(id.value).copy(name = name)
        libraries[id.value] = updated
        return AppResult.Success(updated)
    }

    override suspend fun setInboxEnabled(
        libraryId: LibraryId,
        enabled: Boolean,
    ): AppResult<Library> {
        val updated = libraries.getValue(libraryId.value).copy(inboxEnabled = enabled)
        libraries[libraryId.value] = updated
        return AppResult.Success(updated)
    }

    override suspend fun deleteLibrary(id: LibraryId): AppResult<Unit> {
        libraries.remove(id.value)
        return AppResult.Success(Unit)
    }

    override suspend fun addFolder(
        libraryId: LibraryId,
        path: String,
    ): AppResult<LibraryFolder> {
        addFolderCalls += libraryId.value to path
        val folderId = FolderId("f${folderSeq++}")
        val existing = libraries.getValue(libraryId.value)
        libraries[libraryId.value] =
            existing.copy(folders = existing.folders + LibraryFolderRef(id = folderId, rootPath = path))
        return AppResult.Success(
            LibraryFolder(id = folderId, libraryId = libraryId, rootPath = path, createdAt = 1L),
        )
    }

    override suspend fun removeFolder(folderId: FolderId): AppResult<Unit> {
        removedFolderIds += folderId.value
        return AppResult.Success(Unit)
    }

    override suspend fun scanLibrary(libraryId: LibraryId): AppResult<Unit> {
        scannedLibraryIds += libraryId.value
        return AppResult.Success(Unit)
    }

    override suspend fun scanFolder(folderId: FolderId): AppResult<Unit> = AppResult.Success(Unit)
}

private class FakeLibraryAdminRpcFactory(
    private val service: FakeLibraryAdminService,
) : LibraryAdminRpcFactory {
    override suspend fun get(): LibraryAdminService = service

    override suspend fun invalidate() = Unit
}

private fun buildRepo(service: FakeLibraryAdminService): AdminRepositoryImpl =
    AdminRepositoryImpl(
        adminApi = mock<AdminApiContract>(),
        adminUserRpc = mock<AdminUserRpcFactory>(),
        adminSettingsRpc = mock<AdminSettingsRpcFactory>(),
        inviteRpc = mock<InviteRpcFactory>(),
        libraryAdminRpc = FakeLibraryAdminRpcFactory(service),
        serverConfig = mock<ServerConfig>(),
    )

// ─── Tests ──────────────────────────────────────────────────────────────────────

class AdminRepositoryImplLibraryTest :
    FunSpec({

        test("getLibraries maps contract libraries to domain with folders populated") {
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

            val result = repo.getLibraries()

            (result is AppResult.Success) shouldBe true
            val libs = (result as AppResult.Success).data
            libs.size shouldBe 1
            libs.first().id shouldBe "lib1"
            libs.first().folders.map { it.id } shouldBe listOf("f1", "f2")
            libs.first().folders.map { it.rootPath } shouldBe listOf("/audiobooks", null)
        }
    })
