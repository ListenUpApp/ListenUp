package com.calypsan.listenup.api.dto.match

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val AUDIBLE = MetadataSource(id = "audible", label = "Audible")
private val HARDCOVER = MetadataSource(id = "hardcover", label = "Hardcover")
private val KEY = BookCandidateKey(listOf(ExternalRef("audible", "B08G9PRS1K", "us"), ExternalRef("hardcover", "427578")))

private val REVIEW =
    BookMatchReview(
        candidate = KEY,
        region = MetadataLocale("us"),
        basedOnRevision = 42L,
        fields =
            listOf(
                FieldReview(
                    field = BookField.DESCRIPTION,
                    current = FieldValue.Text("Old"),
                    options =
                        listOf(
                            FieldOption("audible:abc", FieldValue.Text("New"), listOf(AUDIBLE, HARDCOVER)),
                        ),
                    defaultChoice = FieldChoice.Option("audible:abc"),
                    state = FieldState.CHANGES,
                    handEdit = null,
                ),
                FieldReview(
                    field = BookField.AUTHORS,
                    current = FieldValue.People(listOf("Andy Weir")),
                    options = listOf(FieldOption("audible:p", FieldValue.People(listOf("Andy Weir")), listOf(AUDIBLE))),
                    defaultChoice = FieldChoice.KeepCurrent,
                    state = FieldState.SAME,
                    handEdit = HandEdit(byUserId = "u1", byName = "Sam", at = 1_700_000_000_000L),
                ),
                FieldReview(
                    field = BookField.SERIES,
                    current = null,
                    options =
                        listOf(
                            FieldOption(
                                "audible:s",
                                FieldValue.SeriesEntries(listOf(MatchSeriesEntry("Bobiverse", "1.5"))),
                                listOf(AUDIBLE),
                            ),
                        ),
                    defaultChoice = FieldChoice.Option("audible:s"),
                    state = FieldState.FILLS_GAP,
                    handEdit = HandEdit(byUserId = null, byName = null, at = null),
                ),
                FieldReview(
                    field = BookField.PUBLISH_YEAR,
                    current = FieldValue.Year(2020),
                    options = listOf(FieldOption("audible:y", FieldValue.Year(2021), listOf(AUDIBLE))),
                    defaultChoice = FieldChoice.KeepCurrent,
                    state = FieldState.USER_EDITED,
                    handEdit = null,
                ),
            ),
        cover =
            CoverReview(
                current = CurrentCover(hash = "h1", setByHand = true),
                options = listOf(CoverCandidate("hardcover:c", HARDCOVER, "https://x/y.jpg", 500, 800)),
                defaultChoice = ImageChoice.KeepCurrent,
            ),
        genres = LabelSetReview(listOf("Fantasy"), listOf(LabelSuggestion("Science Fiction", listOf(AUDIBLE)))),
        moods = LabelSetReview(emptyList(), emptyList()),
        chapterNames =
            ChapterNamesReview.Available(AUDIBLE, listOf(ChapterNameChange(0, "Chapter 1", "Opening Credits")), 35),
    )

private val RECEIPT =
    MatchReceipt(
        receiptId = "r1",
        appliedAt = 1_700_000_000_000L,
        changes =
            listOf(
                AppliedChange.Field(BookField.DESCRIPTION, AUDIBLE),
                AppliedChange.Cover(HARDCOVER),
                AppliedChange.Genres(listOf("Science Fiction"), listOf("Fantasy")),
                AppliedChange.Moods(listOf("Hopeful"), emptyList()),
                AppliedChange.ChapterNames(16, AUDIBLE),
                AppliedChange.Photo(AUDIBLE),
                AppliedChange.Biography(HARDCOVER),
            ),
        undoable = true,
    )

private inline fun <reified T> roundTrip(value: T): T = contractJson.decodeFromString(contractJson.encodeToString(value))

class BookReviewContractTest :
    FunSpec({
        test("a book match review round-trips") {
            roundTrip(REVIEW) shouldBe REVIEW
        }

        test("every chapter-names review shape round-trips") {
            val shapes: List<ChapterNamesReview> =
                listOf(
                    REVIEW.chapterNames,
                    ChapterNamesReview.CountMismatch(AUDIBLE, yours = 30, theirs = 36),
                    ChapterNamesReview.Unavailable,
                )
            shapes.forEach { roundTrip(it) shouldBe it }
        }

        test("an apply request round-trips, every choice shape included") {
            val apply =
                BookMatchApply(
                    candidate = KEY,
                    region = MetadataLocale("uk"),
                    basedOnRevision = 42L,
                    fields =
                        listOf(
                            FieldDecision(BookField.TITLE, FieldChoice.KeepCurrent),
                            FieldDecision(BookField.DESCRIPTION, FieldChoice.Option("audible:abc")),
                        ),
                    cover = ImageChoice.Candidate("hardcover:c"),
                    genres = LabelSetChange(add = listOf("Science Fiction"), remove = listOf("Fantasy")),
                    moods = LabelSetChange(),
                    chapterOrdinals = listOf(0, 1, 2),
                )
            roundTrip(apply) shouldBe apply
            val keep = apply.copy(cover = ImageChoice.KeepCurrent, region = null)
            roundTrip(keep) shouldBe keep
        }

        test("a receipt, an undo result and a last match round-trip") {
            roundTrip(RECEIPT) shouldBe RECEIPT
            val undo = UndoResult(receiptId = "r1", restored = RECEIPT.changes)
            roundTrip(undo) shouldBe undo
            val last = LastMatch("r1", 1_700_000_000_000L, "u1", 43L, RECEIPT.changes)
            roundTrip(last) shouldBe last
        }

        test("the new metadata errors round-trip as AppErrors and are honest about retry") {
            val errors: List<AppError> =
                listOf(
                    MetadataError.ExternalTimeout(debugInfo = "audible"),
                    MetadataError.ReviewOutdated(),
                    MetadataError.CoverDownloadFailed(),
                    MetadataError.UndoExpired(),
                )
            errors.forEach { roundTrip(it) shouldBe it }
            errors.map { it.isRetryable } shouldBe listOf(true, false, false, false)
        }

        test("a book payload without lastMatch still decodes, and one with it round-trips") {
            val payload =
                BookSyncPayload(
                    id = "b1",
                    libraryId = LibraryId("l1"),
                    folderId = FolderId("f1"),
                    title = "T",
                    sortTitle = null,
                    subtitle = null,
                    description = null,
                    publishYear = null,
                    publisher = null,
                    language = null,
                    isbn = null,
                    asin = null,
                    abridged = false,
                    explicit = false,
                    totalDuration = 0L,
                    cover = null,
                    rootRelPath = "a",
                    inode = null,
                    scannedAt = 0L,
                    contributors = emptyList(),
                    series = emptyList(),
                    audioFiles = emptyList(),
                    chapters = emptyList(),
                    revision = 1L,
                    updatedAt = 0L,
                    createdAt = 0L,
                    deletedAt = null,
                )
            val legacyJson = contractJson.encodeToString(payload).replace(",\"lastMatch\":null", "")
            contractJson.decodeFromString<BookSyncPayload>(legacyJson).lastMatch shouldBe null
            val matched = payload.copy(lastMatch = LastMatch("r1", 5L, "u1", 1L, RECEIPT.changes))
            roundTrip(matched) shouldBe matched
        }
    })
