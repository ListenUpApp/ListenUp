package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.io.writeBytes
import com.calypsan.listenup.server.matching.apply.BOOK
import com.calypsan.listenup.server.matching.apply.MatchRig
import com.calypsan.listenup.server.matching.apply.US
import com.calypsan.listenup.server.matching.undo.BookCoverReferences
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val PAST_GRACE = FixedClock(Clock.System.now() + OrphanImageCleanupTask.ORPHAN_GRACE * 2)

private fun MatchRig.sweeper(
    references: CoverReferences = BookCoverReferences(db.sql, receipts),
    clock: Clock = PAST_GRACE,
) = OrphanImageCleanupTask(contributors, series, Path(home), coverReferences = references, clock = clock)

private fun MatchRig.coverFile(name: String): Path {
    SystemFileSystem.createDirectories(Path(coversDir))
    return Path(coversDir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
}

private fun Path.exists() = SystemFileSystem.exists(this)

/** Gives the book a managed cover file, as the scanner or a hand upload writes it: `covers/<bookId>.jpg`. */
private suspend fun MatchRig.scannedCover(): Path =
    coverFile("$BOOK.jpg").also {
        books.setManagedCover(BookId(BOOK), "covers/$BOOK.jpg", "h-scan", CoverSource.EMBEDDED).shouldSucceed()
    }

/**
 * The `covers/` keep rule (decision D1): the first sweep that can delete a user's cover. A file is kept when any
 * book row names it (soft-deleted ones too), when a live match receipt keeps it for Undo, or while it is younger
 * than the grace window; and a sweep that can't read what is live deletes nothing.
 */
class OrphanCoverSweepTest :
    FunSpec({
        test("a scanner- or upload-written covers/<bookId>.jpg its row names is never swept") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val file = rig.scannedCover()
                    rig.sweeper().runOnce()
                    file.exists() shouldBe true
                }
            }
        }

        test("a cover only a soft-deleted book names is kept, so reviving the book keeps its cover") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val file = rig.scannedCover()
                    // Another, live book with a cover, so the sweep runs at all (an all-empty read fails closed).
                    rig.db.sql.seedTestBook("other")
                    rig.coverFile("other.jpg")
                    rig.books.setManagedCover(BookId("other"), "covers/other.jpg", "h-other", CoverSource.EMBEDDED).shouldSucceed()
                    rig.books.softDelete(BookId(BOOK)).shouldSucceed()
                    rig.sweeper().runOnce()
                    file.exists() shouldBe true
                }
            }
        }

        test("the cover a live receipt keeps for Undo survives the sweep, and is reaped once the receipt is gone") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val old = rig.scannedCover()
                    rig.applier.apply(rig.book(), rig.fullRequest(), US, "u1").shouldSucceed()
                    rig.coverColumns().cover_path!! shouldStartWith "covers/$BOOK-"

                    rig.sweeper().runOnce()
                    old.exists() shouldBe true

                    rig.books.touchRevision(BookId(BOOK)).shouldSucceed()
                    rig.receipts.deleteDead()
                    rig.sweeper().runOnce()
                    old.exists() shouldBe false
                    Path(rig.home, rig.coverColumns().cover_path!!).exists() shouldBe true
                }
            }
        }

        test("an undone match's cover is reaped, and the restored one kept") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val old = rig.scannedCover()
                    val receipt = rig.applier.apply(rig.book(), rig.fullRequest(), US, "u1").shouldSucceed()
                    val matched = Path(rig.home, rig.coverColumns().cover_path!!)
                    rig.undoer.undo(receipt.receiptId).shouldSucceed()
                    rig.sweeper().runOnce()
                    matched.exists() shouldBe false
                    old.exists() shouldBe true
                }
            }
        }

        test("an unreferenced cover younger than the grace window is kept; an older one is deleted") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    rig.scannedCover()
                    val stray = rig.coverFile("$BOOK-stray.jpg")
                    rig.sweeper(clock = Clock.System).runOnce()
                    stray.exists() shouldBe true
                    rig.sweeper().runOnce()
                    stray.exists() shouldBe false
                }
            }
        }

        test("fail closed: when what is live can't be read, nothing is deleted") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    rig.scannedCover()
                    val stray = rig.coverFile("$BOOK-stray.jpg")
                    val broken =
                        object : CoverReferences {
                            override suspend fun bookCoverPaths(): Set<String> = error("database unreadable")

                            override suspend fun pinnedCoverPaths(): Set<String> = emptySet()
                        }
                    rig.sweeper(references = broken).runOnce()
                    stray.exists() shouldBe true
                }
            }
        }

        test("fail closed: a live receipt whose snapshot can't be read pins everything") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    rig.scannedCover()
                    val stray = rig.coverFile("$BOOK-stray.jpg")
                    rig.db.sql.matchReceiptsQueries
                        .insert("r-bad", "book", "other", "u1", 1L, 1L, "{not json", "[]")
                    rig.sweeper().runOnce()
                    stray.exists() shouldBe true
                }
            }
        }

        test("fail closed: when no book names any cover at all, nothing is deleted") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val stray = rig.coverFile("$BOOK-stray.jpg")
                    rig.sweeper().runOnce()
                    stray.exists() shouldBe true
                }
            }
        }
    })
