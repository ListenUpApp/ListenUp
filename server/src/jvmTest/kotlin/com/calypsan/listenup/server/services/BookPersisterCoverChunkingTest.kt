@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.CoverSource
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.dto.scanner.withoutArtwork
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedArtwork
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

/**
 * PERF-01: [BookPersister.persistAll] extracts cover bytes in `COVER_EXTRACTION_CHUNK_SIZE`-sized
 * slices instead of for the whole folder group (worst case: a single-folder library's entire
 * changed-book set) up front — bounding peak cover heap during a scan. A sibling of
 * [BookPersisterTest] (split out per Detekt's `LargeClass`), reusing its [FakeBookIngest] /
 * [persister] / [analyzedBook] / [scanResult] fixtures.
 */
class BookPersisterCoverChunkingTest :
    FunSpec({

        test("a scan spanning multiple cover-extraction chunks persists every book and threads every cover through") {
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest()
                    // A non-null CoverImageStore unlocks BookPersister's cover-extraction path — it only
                    // null-checks the store (the actual managed-file write is BookRepository's job, out of
                    // scope for this orchestration test); the directory is never touched because
                    // FakeBookIngest discards pendingCover before any disk write would happen.
                    val coverStore = CoverImageStore(ImageStore(Path("/unused-cover-store"), maxBytes = 10L * 1024 * 1024))
                    val persister = persister(fake, scope = this, coverImageStore = coverStore)

                    // 251 books — one more than BookPersister's COVER_EXTRACTION_CHUNK_SIZE (250) —
                    // forces the write loop through two cover-extraction slices for a single folder
                    // group. Each book's embedded cover carries a distinct byte payload so a
                    // slice-boundary bug (a dropped or cross-contaminated cover) is caught precisely
                    // rather than only by an aggregate count.
                    val bookCount = 251
                    val books =
                        (0 until bookCount).map { i ->
                            analyzedBook("book-$i").copy(
                                cover =
                                    CoverSource.Embedded(
                                        EmbeddedArtwork(mime = "image/jpeg", bytes = byteArrayOf(i.toByte(), (i + 1).toByte())),
                                    ),
                            )
                        }
                    val changes = books.map { ChangeEventDto.Added(it.withoutArtwork()) }

                    persister.persist(scanResult(books = books, changes = changes, scope = ScanScope.Full))

                    // Every book persisted...
                    fake.resolved shouldHaveSize bookCount
                    // ...and every book's cover survived the chunked extraction with the RIGHT bytes —
                    // proof no slice dropped or mixed up a cover at the 250-book chunk boundary.
                    for (i in 0 until bookCount) {
                        val pending = fake.pendingCoverByPath["book-$i"].shouldNotBeNull()
                        pending.bytes shouldBe byteArrayOf(i.toByte(), (i + 1).toByte())
                    }
                }
            }
        }
    })
