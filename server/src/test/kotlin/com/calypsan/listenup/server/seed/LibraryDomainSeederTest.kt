package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.dto.AccessMode
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolder
import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.error.LibraryError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Unit tests for [LibraryDomainSeeder].
 *
 * Uses [FakeLibraryAdminService] as the write-path seam — the seeder is not
 * coupled to the concrete [LibraryAdminServiceImpl] with its many transitive
 * dependencies. The DB is still real (in-memory SQLite via Flyway) so the
 * [isAlreadySeeded] check, which reads [LibraryTable] directly, exercises
 * the real schema.
 */
class LibraryDomainSeederTest :
    FunSpec({
        val demoPath = "/tmp/demo-library"

        test("isAlreadySeeded returns false when no libraries exist") {
            withInMemoryDatabase {
                val seeder =
                    LibraryDomainSeeder(
                        db = this,
                        libraryAdminService = FakeLibraryAdminService(),
                        demoLibraryPath = demoPath,
                    )
                runTest { seeder.isAlreadySeeded() shouldBe false }
            }
        }

        test("seed() registers the demo folder path via addFolder") {
            withInMemoryDatabase {
                val fake = FakeLibraryAdminService()
                val seeder =
                    LibraryDomainSeeder(
                        db = this,
                        libraryAdminService = fake,
                        demoLibraryPath = demoPath,
                    )
                runTest {
                    seeder.seed()
                    fake.addFolderCalls.size shouldBe 1
                    fake.addFolderCalls.single() shouldBe demoPath
                }
            }
        }

        test("isAlreadySeeded returns true when a library row exists") {
            withInMemoryDatabase {
                val db = this
                // Bypass the service and insert a library row directly so we
                // can test isAlreadySeeded against the real DB read.
                runTest {
                    suspendTransaction(db) {
                        val now = System.currentTimeMillis()
                        LibraryTable.insert {
                            it[LibraryTable.id] = "lib-1"
                            it[LibraryTable.name] = "Demo Library"
                            it[LibraryTable.metadataPrecedence] = "embedded,abs,sidecar"
                            it[LibraryTable.revision] = 1L
                            it[LibraryTable.createdAt] = now
                            it[LibraryTable.updatedAt] = now
                        }
                    }
                }
                val seeder =
                    LibraryDomainSeeder(
                        db = db,
                        libraryAdminService = FakeLibraryAdminService(),
                        demoLibraryPath = demoPath,
                    )
                runTest { seeder.isAlreadySeeded() shouldBe true }
            }
        }

        test("seed() is idempotent — calling twice does not throw") {
            withInMemoryDatabase {
                // On the second call the service returns DuplicateFolder; seed() must swallow it.
                val fake = FakeLibraryAdminService(failSecondAdd = true)
                val seeder =
                    LibraryDomainSeeder(
                        db = this,
                        libraryAdminService = fake,
                        demoLibraryPath = demoPath,
                    )
                runTest {
                    seeder.seed()
                    seeder.seed() // second call must not throw
                    fake.addFolderCalls.size shouldBe 2
                }
            }
        }

        test("domainName and order are correct") {
            withInMemoryDatabase {
                val seeder =
                    LibraryDomainSeeder(
                        db = this,
                        libraryAdminService = FakeLibraryAdminService(),
                        demoLibraryPath = demoPath,
                    )
                seeder.domainName shouldBe "library"
                seeder.order shouldBe 5
            }
        }
    })

// ── Test helpers ────────────────────────────────────────────────────────────

/**
 * Minimal fake [com.calypsan.listenup.api.LibraryAdminService] that records
 * [addFolder] calls and optionally returns [LibraryError.DuplicateFolder]
 * on the second call to simulate the idempotency scenario.
 */
private class FakeLibraryAdminService(
    private val failSecondAdd: Boolean = false,
) : com.calypsan.listenup.api.LibraryAdminService {
    val addFolderCalls = mutableListOf<String>()

    override suspend fun getLibrary(): AppResult<Library> =
        AppResult.Success(
            Library(
                id = LibraryId("lib-1"),
                name = "Demo Library",
                folders = emptyList(),
                metadataPrecedence = "embedded,abs,sidecar",
                accessMode = AccessMode.SHARED,
                createdByUserId = null,
                createdAt = System.currentTimeMillis(),
            ),
        )

    override suspend fun getSetupStatus(): AppResult<SetupStatus> =
        AppResult.Success(SetupStatus(needsSetup = true, isScanning = false))

    override suspend fun browseFilesystem(path: String): AppResult<List<DirectoryEntry>> = AppResult.Success(emptyList())

    override suspend fun addFolder(path: String): AppResult<LibraryFolder> {
        addFolderCalls.add(path)
        if (failSecondAdd && addFolderCalls.size > 1) {
            return AppResult.Failure(LibraryError.DuplicateFolder())
        }
        return AppResult.Success(
            LibraryFolder(
                id = FolderId("folder-${addFolderCalls.size}"),
                libraryId = LibraryId("lib-1"),
                rootPath = path,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun removeFolder(folderId: FolderId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun scanLibrary(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun scanFolder(folderId: FolderId): AppResult<Unit> = AppResult.Success(Unit)
}
