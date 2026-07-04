package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.dao.LibraryDao
import com.calypsan.listenup.client.data.local.db.dao.LibraryFolderDao
import com.calypsan.listenup.client.data.local.db.entity.LibraryEntity
import com.calypsan.listenup.client.data.local.db.entity.LibraryFolderEntity
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.model.LibraryFolder
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [LibraryRepositoryImpl].
 *
 * Verifies the entity-to-domain mapping for [Library] and [LibraryFolder], and
 * that each repository method delegates to the correct DAO and returns the
 * expected domain values. Uses Mokkery stubs — no Room database required.
 */
class LibraryRepositoryImplTest :
    FunSpec({

        val libraryEntity =
            LibraryEntity(
                id = "lib-1",
                name = "My Library",
                metadataPrecedence = "embedded,abs",
                accessMode = "open",
                createdByUserId = null,
                createdAt = 1_000L,
                revision = 3L,
                deletedAt = null,
                initialScanCompletedAt = null,
            )

        val expectedLibrary =
            Library(
                id = "lib-1",
                name = "My Library",
                metadataPrecedence = "embedded,abs",
                accessMode = AccessMode.OPEN,
                createdByUserId = null,
                createdAt = 1_000L,
                revision = 3L,
            )

        val folderEntity =
            LibraryFolderEntity(
                id = "f-1",
                libraryId = "lib-1",
                rootPath = "/audiobooks",
                createdAt = 2_000L,
                revision = 1L,
                deletedAt = null,
            )

        val expectedFolder =
            LibraryFolder(
                id = "f-1",
                libraryId = "lib-1",
                rootPath = "/audiobooks",
                createdAt = 2_000L,
                revision = 1L,
            )

        fun makeRepo(
            libraryDao: LibraryDao,
            folderDao: LibraryFolderDao,
        ) = LibraryRepositoryImpl(libraryDao, folderDao)

        // ========== observeAll ==========

        test("observeAll maps entities to domain Library list") {
            runTest {
                val libraryDao = mock<LibraryDao>(MockMode.autoUnit)
                val folderDao = mock<LibraryFolderDao>(MockMode.autoUnit)
                every { libraryDao.observeAll() } returns flowOf(listOf(libraryEntity))

                val result = makeRepo(libraryDao, folderDao).observeAll().first()

                result shouldBe listOf(expectedLibrary)
            }
        }

        test("observeAll maps accessMode restricted correctly") {
            runTest {
                val libraryDao = mock<LibraryDao>(MockMode.autoUnit)
                val folderDao = mock<LibraryFolderDao>(MockMode.autoUnit)
                every { libraryDao.observeAll() } returns
                    flowOf(listOf(libraryEntity.copy(accessMode = "restricted")))

                val result = makeRepo(libraryDao, folderDao).observeAll().first()

                result.first().accessMode shouldBe AccessMode.RESTRICTED
            }
        }

        test("observeAll returns empty list when no libraries") {
            runTest {
                val libraryDao = mock<LibraryDao>(MockMode.autoUnit)
                val folderDao = mock<LibraryFolderDao>(MockMode.autoUnit)
                every { libraryDao.observeAll() } returns flowOf(emptyList())

                val result = makeRepo(libraryDao, folderDao).observeAll().first()

                result shouldBe emptyList()
            }
        }

        // ========== observeById ==========

        test("observeById maps entity to domain Library") {
            runTest {
                val libraryDao = mock<LibraryDao>(MockMode.autoUnit)
                val folderDao = mock<LibraryFolderDao>(MockMode.autoUnit)
                every { libraryDao.observeById("lib-1") } returns flowOf(libraryEntity)

                val result = makeRepo(libraryDao, folderDao).observeById("lib-1").first()

                result shouldBe expectedLibrary
            }
        }

        test("observeById emits null when entity absent") {
            runTest {
                val libraryDao = mock<LibraryDao>(MockMode.autoUnit)
                val folderDao = mock<LibraryFolderDao>(MockMode.autoUnit)
                every { libraryDao.observeById("missing") } returns flowOf(null)

                val result = makeRepo(libraryDao, folderDao).observeById("missing").first()

                result shouldBe null
            }
        }

        // ========== observeFolders ==========

        test("observeFolders maps entities to domain LibraryFolder list") {
            runTest {
                val libraryDao = mock<LibraryDao>(MockMode.autoUnit)
                val folderDao = mock<LibraryFolderDao>(MockMode.autoUnit)
                every { folderDao.observeForLibrary("lib-1") } returns flowOf(listOf(folderEntity))

                val result = makeRepo(libraryDao, folderDao).observeFolders("lib-1").first()

                result shouldBe listOf(expectedFolder)
            }
        }

        // ========== findById ==========

        test("findById returns domain Library when present") {
            runTest {
                val libraryDao = mock<LibraryDao>(MockMode.autoUnit)
                val folderDao = mock<LibraryFolderDao>(MockMode.autoUnit)
                everySuspend { libraryDao.findById("lib-1") } returns libraryEntity

                val result = makeRepo(libraryDao, folderDao).findById("lib-1")

                result shouldBe expectedLibrary
            }
        }

        test("findById returns null when absent") {
            runTest {
                val libraryDao = mock<LibraryDao>(MockMode.autoUnit)
                val folderDao = mock<LibraryFolderDao>(MockMode.autoUnit)
                everySuspend { libraryDao.findById("missing") } returns null

                val result = makeRepo(libraryDao, folderDao).findById("missing")

                result shouldBe null
            }
        }

        // ========== findFolderById ==========

        test("findFolderById returns domain LibraryFolder when present") {
            runTest {
                val libraryDao = mock<LibraryDao>(MockMode.autoUnit)
                val folderDao = mock<LibraryFolderDao>(MockMode.autoUnit)
                everySuspend { folderDao.findById("f-1") } returns folderEntity

                val result = makeRepo(libraryDao, folderDao).findFolderById("f-1")

                result shouldNotBe null
                result shouldBe expectedFolder
            }
        }

        test("findFolderById returns null when absent") {
            runTest {
                val libraryDao = mock<LibraryDao>(MockMode.autoUnit)
                val folderDao = mock<LibraryFolderDao>(MockMode.autoUnit)
                everySuspend { folderDao.findById("missing") } returns null

                val result = makeRepo(libraryDao, folderDao).findFolderById("missing")

                result shouldBe null
            }
        }
    })
