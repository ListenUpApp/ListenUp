@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Book identity must anchor on the first AUDIO file's inode, not the first file
 * of any type. A cover image (`AlbumArt.jpg`) name-sorts ahead of the audio, so
 * anchoring on `files.first()` let a cover replacement + folder rename re-mint
 * the book UUID and sweep away the old row — losing the user's positions,
 * progress, shelves, and edits. The Differ already matches moves on audio-file
 * inodes; identity must agree with it.
 */
class BookIdentityInodeAnchorTest :
    FunSpec({

        test("cover replacement + folder rename keeps the same book UUID (audio inode unchanged)") {
            withSqlDatabase {
                val (repo, registry) = identityRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()

                    // Initial scan: a cover image (inode 100) sorts before the audio (inode 200).
                    val firstScan =
                        bookWith(
                            rootRelPath = "Sanderson/Mistborn",
                            coverInode = 100L,
                            audioInode = 200L,
                        )
                    val originalId = repo.resolveOrInsert(libId, IDENTITY_FOLDER, firstScan).resolved()

                    // Rescan: folder renamed (path miss), cover replaced (new inode 101),
                    // audio file unchanged (still inode 200).
                    val rescan =
                        bookWith(
                            rootRelPath = "Sanderson/Mistborn - The Final Empire",
                            coverInode = 101L,
                            audioInode = 200L,
                        )
                    val rescanOutcome = repo.resolveOrInsert(libId, IDENTITY_FOLDER, rescan)

                    // Same book resolved — not a fresh UUID, old row not swept.
                    when (rescanOutcome) {
                        is AppResult.Success -> {
                            rescanOutcome.data.bookId shouldBe originalId
                            rescanOutcome.data.wasNew shouldBe false
                        }

                        is AppResult.Failure -> {
                            error("rescan failed: ${rescanOutcome.error.message}")
                        }
                    }
                    // The renamed path is now the book's path — the old one is gone (moved, not duplicated).
                    repo.findById(originalId)!!.rootRelPath shouldBe "Sanderson/Mistborn - The Final Empire"
                }
            }
        }
    })

private val IDENTITY_FOLDER = FolderId("identity-folder")

private fun AppResult<IngestOutcome>.resolved(): BookId =
    when (this) {
        is AppResult.Success -> data.bookId
        is AppResult.Failure -> error("resolveOrInsert failed: ${error.message}")
    }

private fun bookWith(
    rootRelPath: String,
    coverInode: Long,
    audioInode: Long,
): AnalyzedBook {
    // Cover file name-sorts before the audio file, mirroring the Walker's name-sorted order.
    val cover =
        FileEntry(
            relPath = "$rootRelPath/AlbumArt.jpg",
            name = "AlbumArt.jpg",
            ext = "jpg",
            size = 2048L,
            mtimeMs = 0L,
            inode = coverInode,
            fileType = FileType.IMAGE,
        )
    val audio =
        FileEntry(
            relPath = "$rootRelPath/Book.m4b",
            name = "Book.m4b",
            ext = "m4b",
            size = 4096L,
            mtimeMs = 0L,
            inode = audioInode,
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate = CandidateBook(rootRelPath = rootRelPath, isFile = false, files = listOf(cover, audio)),
        title = rootRelPath.substringAfterLast('/'),
        tracks = listOf(TrackEntry(file = audio)),
    )
}

private fun identityRepository(
    sql: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
): Pair<BookRepository, LibraryRegistry> {
    val registry = LibraryRegistry(sql)
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val contributorRepo = ContributorRepository(sql, bus, syncRegistry)
    val seriesRepo = SeriesRepository(sql, bus, syncRegistry)
    val repo =
        BookRepository(
            db = sql,
            driver = driver,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = GenreRepository(sql, bus, syncRegistry),
        )
    return repo to registry
}
