package com.calypsan.listenup.server.matching.person

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.ContributorField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.matching.apply.MatchRig
import com.calypsan.listenup.server.matching.apply.US
import com.calypsan.listenup.server.sync.withCapturedFrames
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private fun AppResult<*>.error() = (this as AppResult.Failure).error

/** Person Undo (spec, *Undo*): restores everything the match touched while the person is unchanged. */
class PersonMatchUndoTest :
    FunSpec({
        test("undo restores the bio, the photo, the refs, the asin and the provenance, as a new revision") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val bioEdit = FieldProvenance(FieldSourceKind.USER, at = 5L, by = "u9")
                    rig.contributors
                        .upsert(rig.person().copy(fieldProvenance = mapOf(ContributorField.BIOGRAPHY to bioEdit)))
                        .shouldSucceed()
                    val before = rig.person()
                    val receipt = rig.apply(rig.mixedRequest()).shouldSucceed()
                    val applied = rig.person()

                    val undone = withCapturedFrames { rig.undoer.undo(receipt.receiptId) }.shouldSucceed()
                    undone.value.restored shouldBe receipt.changes
                    undone.frames.count { it.domain == SyncDomains.CONTRIBUTORS.name } shouldBe 1

                    val after = rig.person()
                    (after.revision > applied.revision) shouldBe true
                    after.copy(revision = before.revision, updatedAt = before.updatedAt) shouldBe before
                }
            }
        }

        test("any change to the person expires the undo") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val receipt = rig.apply(rig.mixedRequest()).shouldSucceed()
                    rig.contributors.upsert(rig.person().copy(website = "https://ray.example")).shouldSucceed()
                    val moved = rig.person()
                    rig.undoer.undo(receipt.receiptId).error().shouldBeInstanceOf<MetadataError.UndoExpired>()
                    rig.person() shouldBe moved
                }
            }
        }

        test("a second undo fails") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val receipt = rig.apply(rig.mixedRequest()).shouldSucceed()
                    rig.undoer.undo(receipt.receiptId).shouldSucceed()
                    rig.undoer.undo(receipt.receiptId).error().shouldBeInstanceOf<MetadataError.UndoExpired>()
                }
            }
        }

        test("a book's receipt is never undone as a person's, nor a person's as a book's") {
            withSqlDatabase {
                runTest {
                    val books = MatchRig(this@withSqlDatabase)
                    books.seedBook()
                    val bookReceipt = books.applier.apply(books.book(), books.fullRequest(), US, "u1").shouldSucceed()
                    val people = PersonRig(this@withSqlDatabase)
                    people.seedRay()
                    val personReceipt = people.apply(people.mixedRequest()).shouldSucceed()

                    people.undoer.undo(bookReceipt.receiptId).error().shouldBeInstanceOf<MetadataError.UndoExpired>()
                    books.undoer.undo(personReceipt.receiptId).error().shouldBeInstanceOf<MetadataError.UndoExpired>()
                }
            }
        }

        test("the receipt sweep deletes person receipts that can no longer be undone, and keeps the live one") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val undone = rig.apply(rig.mixedRequest()).shouldSucceed()
                    rig.undoer.undo(undone.receiptId).shouldSucceed()
                    val live = rig.apply(rig.mixedRequest()).shouldSucceed()
                    rig.receipts.deleteDead()
                    rig.receipts.find(undone.receiptId) shouldBe null
                    rig.receipts.find(live.receiptId)!!.entityId shouldBe rig.rayId

                    rig.contributors.upsert(rig.person().copy(website = "https://ray.example")).shouldSucceed()
                    rig.receipts.deleteDead()
                    rig.receipts.find(live.receiptId) shouldBe null
                }
            }
        }

        test("a merged-away person's receipt is dead") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val receipt = rig.apply(rig.mixedRequest()).shouldSucceed()
                    rig.contributors.softDelete(ContributorId(rig.rayId)).shouldSucceed()
                    rig.undoer.undo(receipt.receiptId).error().shouldBeInstanceOf<MetadataError.UndoExpired>()
                    rig.receipts.deleteDead()
                    rig.receipts.find(receipt.receiptId) shouldBe null
                }
            }
        }
    })
