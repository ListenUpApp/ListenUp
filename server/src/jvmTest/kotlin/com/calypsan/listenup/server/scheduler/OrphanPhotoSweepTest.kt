package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.io.writeBytes
import com.calypsan.listenup.server.matching.apply.BOOK
import com.calypsan.listenup.server.matching.apply.MatchRig
import com.calypsan.listenup.server.matching.person.PersonRig
import com.calypsan.listenup.server.matching.undo.BookCoverReferences
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val PAST_GRACE = FixedClock(Clock.System.now() + OrphanImageCleanupTask.ORPHAN_GRACE * 2)

private fun PersonRig.sweeper(
    pins: PhotoPins? = PhotoPins { receipts.pinnedPhotoPaths() },
    home: String = this.home,
) = OrphanImageCleanupTask(
    contributors,
    SeriesRepository(db.sql, bus, SyncRegistry()),
    Path(home),
    photoPins = pins,
    clock = PAST_GRACE,
)

private fun PersonRig.photoFile(name: String): Path {
    SystemFileSystem.createDirectories(Path(home, "contributors"))
    return Path(home, "contributors", name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
}

private fun Path.exists() = SystemFileSystem.exists(this)

/**
 * The `contributors/` keep rule with person matches: the photo a live person receipt keeps for Undo survives the
 * sweep, and a sweep that can't read those receipts deletes nothing there — the same fail-closed rule as covers.
 */
class OrphanPhotoSweepTest :
    FunSpec({
        test("the photo a live person receipt keeps for Undo survives, and is reaped once the receipt is gone") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val old = rig.photoFile("old.jpg")
                    rig.apply(rig.mixedRequest()).shouldSucceed()

                    rig.sweeper().runOnce()
                    old.exists() shouldBe true

                    rig.contributors.upsert(rig.person().copy(website = "https://ray.example")).shouldSucceed()
                    rig.receipts.deleteDead()
                    rig.sweeper().runOnce()
                    old.exists() shouldBe false
                    Path(rig.home, rig.person().imagePath!!).exists() shouldBe true
                }
            }
        }

        test("an undone match's photo is reaped, and the restored one kept") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val old = rig.photoFile("old.jpg")
                    val receipt = rig.apply(rig.mixedRequest()).shouldSucceed()
                    val matched = Path(rig.home, rig.person().imagePath!!)
                    rig.undoer.undo(receipt.receiptId).shouldSucceed()
                    rig.sweeper().runOnce()
                    matched.exists() shouldBe false
                    old.exists() shouldBe true
                }
            }
        }

        test("fail closed: a live person receipt whose snapshot can't be read leaves contributors/ untouched") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    rig.photoFile("old.jpg")
                    val stray = rig.photoFile("stray.jpg")
                    rig.db.sql.matchReceiptsQueries
                        .insert("r-bad", "contributor", "someone", "u1", 1L, 1L, "{not json", "[]")
                    rig.sweeper().runOnce()
                    stray.exists() shouldBe true
                }
            }
        }

        test("fail closed: pins that can't be read leave contributors/ untouched") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val stray = rig.photoFile("stray.jpg")
                    rig.sweeper(pins = PhotoPins { error("database unreadable") }).runOnce()
                    stray.exists() shouldBe true
                }
            }
        }

        test("an unpinned stray photo is still swept") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val stray = rig.photoFile("stray.jpg")
                    rig.sweeper().runOnce()
                    stray.exists() shouldBe false
                }
            }
        }

        test("a live person receipt never makes the covers sweep fail closed") {
            withSqlDatabase {
                runTest {
                    val books = MatchRig(this@withSqlDatabase)
                    books.seedBook()
                    SystemFileSystem.createDirectories(Path(books.coversDir))
                    Path(books.coversDir, "$BOOK.jpg").writeBytes(byteArrayOf(1))
                    books.books
                        .setManagedCover(BookId(BOOK), "covers/$BOOK.jpg", "h", CoverSource.EMBEDDED)
                        .shouldSucceed()
                    val stray = Path(books.coversDir, "$BOOK-stray.jpg").apply { writeBytes(byteArrayOf(2)) }
                    val people = PersonRig(this@withSqlDatabase)
                    people.seedRay()
                    people.apply(people.mixedRequest()).shouldSucceed()

                    OrphanImageCleanupTask(
                        books.contributors,
                        books.series,
                        Path(books.home),
                        coverReferences = BookCoverReferences(books.db.sql, books.receipts),
                        clock = PAST_GRACE,
                    ).runOnce()
                    stray.exists() shouldBe false
                }
            }
        }
    })
