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
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * End-to-end proof that a multi-contributor author string survives the full ingest
 * path: [BookRepository.upsertFromAnalyzed] → `buildContributors` →
 * [ContributorRepository.resolveOrCreate] → junction rows → [BookRepository.findById]
 * returns two distinct contributor rows with the right name + role.
 */
class ContributorParsingIngestE2ETest :
    FunSpec({

        test("contributor split persists through ingest into two distinct rows") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db, bus, registry)
                val series = SeriesRepository(db, bus, registry)
                val bookRepo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributors,
                        seriesRepository = series,
                    )
                runTest {
                    val analyzed = analyzedWith(authors = listOf("Stephen King; Joe Hill - Introduction"))

                    val result =
                        bookRepo.upsertFromAnalyzed(
                            BookId("c-parse-1"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzed,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val saved = bookRepo.findById(BookId("c-parse-1")).shouldNotBeNull()
                    val byName = saved.contributors.associateBy { it.name }
                    byName.keys shouldBe setOf("Stephen King", "Joe Hill")
                    byName.getValue("Stephen King").role shouldBe "author"
                    byName.getValue("Joe Hill").role shouldBe "introduction"
                }
            }
        }
    })

/**
 * Builds a minimal [AnalyzedBook] with one audio file carrying the supplied raw
 * [authors] strings — left unsplit so the ingest path's `buildContributors` does
 * the splitting.
 */
private fun analyzedWith(authors: List<String>): AnalyzedBook {
    val rootRelPath = "King/Contributor Split"
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = null,
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate =
            CandidateBook(
                rootRelPath = rootRelPath,
                isFile = false,
                files = listOf(file),
            ),
        title = rootRelPath.substringAfterLast('/'),
        authors = authors,
        tracks = listOf(TrackEntry(file = file)),
    )
}
