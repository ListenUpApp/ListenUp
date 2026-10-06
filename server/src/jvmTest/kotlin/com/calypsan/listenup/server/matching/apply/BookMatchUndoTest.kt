package com.calypsan.listenup.server.matching.apply

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.withCapturedFrames
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private fun AppResult<*>.error() = (this as AppResult.Failure).error

class BookMatchUndoTest :
    FunSpec({
        test("undo restores every aspect the match touched, provenance included, as a new revision") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    val before = rig.seedBook()
                    val coverBefore = rig.coverColumns()
                    val receipt = rig.applier.apply(before, rig.fullRequest(), US, "u1").shouldSucceed()
                    val applied = rig.book()

                    val undone = withCapturedFrames { rig.undoer.undo(receipt.receiptId) }.shouldSucceed()
                    undone.value.restored shouldBe receipt.changes
                    undone.frames.count { it.domain == SyncDomains.BOOKS.name } shouldBe 1

                    val after = rig.book()
                    (after.revision > applied.revision) shouldBe true
                    after.copy(revision = before.revision, updatedAt = before.updatedAt) shouldBe before
                    after.fieldProvenance shouldBe before.fieldProvenance
                    rig.coverColumns() shouldBe coverBefore
                    rig.moodNames() shouldBe listOf("Hopeful")
                    rig.tagNames() shouldBe listOf("Heist")
                }
            }
        }

        test("the book's lastMatch appears with the apply, and clears on undo") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val receipt = rig.applier.apply(rig.book(), rig.fullRequest(), US, "u1").shouldSucceed()
                    val matched = rig.book().lastMatch.shouldNotBeNull()
                    matched.receiptId shouldBe receipt.receiptId
                    matched.appliedBy shouldBe "u1"
                    matched.changes shouldBe receipt.changes
                    rig.undoer.undo(receipt.receiptId).shouldSucceed()
                    rig.book().lastMatch.shouldBeNull()
                }
            }
        }

        test("any change to the book expires the undo, and its lastMatch disappears") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val receipt = rig.applier.apply(rig.book(), rig.fullRequest(), US, "u1").shouldSucceed()
                    rig.books.touchRevision(BookId(BOOK)).shouldSucceed()
                    rig.book().lastMatch.shouldBeNull()
                    rig.undoer.undo(receipt.receiptId).error().shouldBeInstanceOf<MetadataError.UndoExpired>()
                }
            }
        }

        test("a second undo fails") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val receipt = rig.applier.apply(rig.book(), rig.fullRequest(), US, "u1").shouldSucceed()
                    rig.undoer.undo(receipt.receiptId).shouldSucceed()
                    rig.undoer.undo(receipt.receiptId).error().shouldBeInstanceOf<MetadataError.UndoExpired>()
                }
            }
        }

        test("a new match replaces the book's live receipt, and the old one can't be undone") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val first = rig.applier.apply(rig.book(), rig.fullRequest(), US, "u1").shouldSucceed()
                    val second =
                        rig.applier
                            .apply(rig.book(), rig.fullRequest().copy(chapterOrdinals = emptyList(), genres = com.calypsan.listenup.api.dto.match.LabelSetChange(), moods = com.calypsan.listenup.api.dto.match.LabelSetChange()), US, "u2")
                            .shouldSucceed()
                    rig.book().lastMatch?.receiptId shouldBe second.receiptId
                    rig.undoer.undo(first.receiptId).error().shouldBeInstanceOf<MetadataError.UndoExpired>()
                }
            }
        }

        test("the daily sweep deletes receipts that are undone or expired, and keeps the live one") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val undone = rig.applier.apply(rig.book(), rig.fullRequest(), US, "u1").shouldSucceed()
                    rig.undoer.undo(undone.receiptId).shouldSucceed()
                    rig.receipts.deleteDead()
                    rig.receipts.find(undone.receiptId).shouldBeNull()

                    val live = rig.applier.apply(rig.book(), rig.fullRequest(), US, "u1").shouldSucceed()
                    rig.receipts.deleteDead()
                    rig.receipts.find(live.receiptId).shouldNotBeNull()

                    rig.books.touchRevision(BookId(BOOK)).shouldSucceed()
                    rig.receipts.deleteDead()
                    rig.receipts.find(live.receiptId).shouldBeNull()
                }
            }
        }
    })
