package com.calypsan.listenup.server.matching.apply

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldDecision
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.LabelSetChange
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.api.sync.Mutated
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.sync.withCapturedFrames
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import io.kotest.assertions.throwables.shouldThrowAny

private suspend fun MatchRig.applyCaptured(request: com.calypsan.listenup.api.dto.match.BookMatchApply) =
    withCapturedFrames { applier.apply(book(), request, US, appliedBy = "u1") }

private fun AppResult<*>.error() = (this as AppResult.Failure).error

class BookMatchApplyTest :
    FunSpec({
        test("a full apply writes fields, cover, genres, moods, chapter names and refs, with one book frame") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    val before = rig.seedBook()
                    val applied: Mutated<MatchReceipt> = rig.applyCaptured(rig.fullRequest()).shouldSucceed()
                    val after = rig.book()

                    after.description shouldBe "New description."
                    after.publisher shouldBe "Ballantine"
                    after.publishYear shouldBe 2021
                    after.releaseDate shouldBe "2021-05-04"
                    after.contributors.filter { it.role == ContributorRole.NARRATOR.apiValue }.map { it.name } shouldBe
                        listOf("Ray Porter")
                    after.genres.map { it.name } shouldContainExactlyInAnyOrder listOf("Science Fiction", "Fiction")
                    rig.moodNames() shouldContainExactly listOf("Tense")
                    after.chapters.sortedBy { it.startTime }.map { it.title } shouldBe listOf("Opening", "Chapter 2", "Finale")
                    after.chapterSource shouldBe ChapterSource.USER
                    after.asin shouldBe "B0X"
                    after.externalRefs.map { it.provider to it.id } shouldContainExactlyInAnyOrder
                        listOf("audible" to "B0X", "hardcover" to "77")
                    rig.coverColumns().cover_path!! shouldStartWith "covers/phm-"

                    applied.frames.count { it.domain == SyncDomains.BOOKS.name } shouldBe 1
                    after.revision shouldBe applied.frames.single { it.domain == SyncDomains.BOOKS.name }.revision
                    (after.revision > before.revision) shouldBe true
                    after.lastMatch?.receiptId shouldBe applied.value.receiptId
                    after.lastMatch?.revision shouldBe after.revision
                }
            }
        }

        test("the write publishes exactly one book event, plus one per mood link it changes") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    val book = rig.seedBook()
                    val request = rig.fullRequest()
                    val model = rig.reviewer.review(book, request.candidate, US).shouldSucceed()
                    val draft = MatchPlanner.plan(model, request, emptyList()).shouldSucceed()
                    val plan = rig.preparer.prepare(draft, book, model.currentMoods).shouldSucceed()

                    val mark = rig.bus.mark()
                    rig.writer.write(plan, request.basedOnRevision, "u1").shouldSucceed()
                    val published = rig.bus.mark() - mark
                    published shouldBe (plan.moodsToLink.size + plan.moodsToUnlink.size + 1).toLong()
                    plan.moodsToLink.size + plan.moodsToUnlink.size shouldBe 2
                }
            }
        }

        test("every written field, the cover included, is stamped ENRICHMENT with its source") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    rig.applier.apply(rig.book(), rig.fullRequest(), US, "u1").shouldSucceed()
                    val provenance = rig.book().fieldProvenance
                    listOf(BookField.DESCRIPTION, BookField.PUBLISHER, BookField.PUBLISH_YEAR, BookField.NARRATORS, BookField.COVER)
                        .forEach { field ->
                            provenance[field]?.kind shouldBe FieldSourceKind.ENRICHMENT
                            provenance[field]?.provider shouldBe "audible"
                        }
                }
            }
        }

        test("the receipt says what changed, field by field") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val receipt = rig.applier.apply(rig.book(), rig.fullRequest(), US, "u1").shouldSucceed()
                    receipt.undoable shouldBe true
                    receipt.changes.filterIsInstance<AppliedChange.Genres>().single() shouldBe
                        AppliedChange.Genres(listOf("Science Fiction"), listOf("Fantasy"))
                    receipt.changes.filterIsInstance<AppliedChange.Moods>().single() shouldBe
                        AppliedChange.Moods(listOf("Tense"), listOf("Hopeful"))
                    receipt.changes
                        .filterIsInstance<AppliedChange.ChapterNames>()
                        .single()
                        .count shouldBe 2
                    receipt.changes
                        .filterIsInstance<AppliedChange.Cover>()
                        .single()
                        .source.label shouldBe "Audible"
                }
            }
        }

        test("tags are never touched") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    rig.applier.apply(rig.book(), rig.fullRequest(), US, "u1").shouldSucceed()
                    rig.tagNames() shouldBe listOf("Heist")
                }
            }
        }

        test("a role is replaced as a whole by the chosen source's list") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val review = rig.review()
                    val authors = review.fields.single { it.field == BookField.AUTHORS }
                    val request =
                        rig.fullRequest().copy(
                            fields = listOf(FieldDecision(BookField.AUTHORS, FieldChoice.Option(authors.options.single().optionId))),
                        )
                    rig.applier.apply(rig.book(), request, US, "u1").shouldSucceed()
                    rig
                        .book()
                        .contributors
                        .filter { it.role == ContributorRole.AUTHOR.apiValue }
                        .map { it.name } shouldBe
                        listOf("Andy Weir")
                    rig
                        .book()
                        .contributors
                        .filter { it.role == ContributorRole.NARRATOR.apiValue }
                        .map { it.name } shouldBe
                        listOf("Old Narrator")
                }
            }
        }

        test("a book that changed since the Review is ReviewOutdated, and nothing is written") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val request = rig.fullRequest()
                    rig.books
                        .touchRevision(
                            com.calypsan.listenup.core
                                .BookId(BOOK),
                        ).shouldSucceed()
                    val before = rig.book()
                    rig.applier
                        .apply(before, request, US, "u1")
                        .error()
                        .shouldBeInstanceOf<MetadataError.ReviewOutdated>()
                    rig.book() shouldBe before
                    rig.moodNames() shouldBe listOf("Hopeful")
                }
            }
        }

        test("an option no longer offered is ReviewOutdated") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val request =
                        rig.fullRequest().copy(
                            fields = listOf(FieldDecision(BookField.DESCRIPTION, FieldChoice.Option("audible:gone"))),
                        )
                    rig.applier
                        .apply(rig.book(), request, US, "u1")
                        .error()
                        .shouldBeInstanceOf<MetadataError.ReviewOutdated>()
                }
            }
        }

        test("a cover that can't be downloaded fails the apply, and nothing is written") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.audible.covers =
                        listOf(
                            com.calypsan.listenup.server.metadata.spi
                                .CoverMeta("https://example.test/broken.jpg", sourceKey = "B0X"),
                        )
                    val before = rig.seedBook()
                    val request = rig.fullRequest(coverUrl = "https://example.test/broken.jpg")
                    rig.applier
                        .apply(before, request, US, "u1")
                        .error()
                        .shouldBeInstanceOf<MetadataError.CoverDownloadFailed>()
                    rig.book() shouldBe before
                    rig.moodNames() shouldBe listOf("Hopeful")
                }
            }
        }

        test("a fault at the last moment rolls back fields, genres, moods, chapters, refs and the receipt") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    val before = rig.seedBook()
                    val coverBefore = rig.coverColumns()
                    rig.fault = { error("injected") }
                    shouldThrowAny { rig.applier.apply(before, rig.fullRequest(), US, "u1") }
                    val after = rig.book()
                    after shouldBe before
                    rig.moodNames() shouldBe listOf("Hopeful")
                    rig.coverColumns() shouldBe coverBefore
                    rig.db.sql.matchReceiptsQueries
                        .selectLiveForEntities("book", listOf(BOOK))
                        .executeAsList() shouldBe emptyList()
                }
            }
        }

        test("chapter names when the counts no longer line up is ChapterCountMismatch") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    rig.seedBook()
                    val request = rig.fullRequest()
                    rig.audible.chapters =
                        rig.audible.chapters!!.copy(
                            chapters =
                                rig.audible.chapters!!
                                    .chapters
                                    .take(2),
                        )
                    rig.applier
                        .apply(rig.book(), request, US, "u1")
                        .error()
                        .shouldBeInstanceOf<MetadataError.ChapterCountMismatch>()
                }
            }
        }

        test("keep current leaves a field and the cover as they are") {
            withSqlDatabase {
                runTest {
                    val rig = MatchRig(this@withSqlDatabase)
                    val before = rig.seedBook()
                    val request =
                        rig.fullRequest().copy(
                            fields = listOf(FieldDecision(BookField.DESCRIPTION, FieldChoice.KeepCurrent)),
                            cover = ImageChoice.KeepCurrent,
                            genres = LabelSetChange(),
                            moods = LabelSetChange(),
                            chapterOrdinals = emptyList(),
                        )
                    rig.applier.apply(before, request, US, "u1").shouldSucceed()
                    rig.book().description shouldBe "Old description."
                    rig.coverColumns().cover_path.shouldBeNull()
                }
            }
        }
    })
