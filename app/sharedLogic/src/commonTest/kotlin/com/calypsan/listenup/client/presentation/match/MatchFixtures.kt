package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.BookCandidate
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.BookMatchApply
import com.calypsan.listenup.api.dto.match.BookMatchReview
import com.calypsan.listenup.api.dto.match.ChapterNameChange
import com.calypsan.listenup.api.dto.match.ChapterNamesReview
import com.calypsan.listenup.api.dto.match.CoverCandidate
import com.calypsan.listenup.api.dto.match.CoverReview
import com.calypsan.listenup.api.dto.match.CurrentCover
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldOption
import com.calypsan.listenup.api.dto.match.FieldReview
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.FieldValue
import com.calypsan.listenup.api.dto.match.FoundIn
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.LabelSetReview
import com.calypsan.listenup.api.dto.match.LabelSuggestion
import com.calypsan.listenup.api.dto.match.MatchReason
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.MetadataSource
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.PersonMatchApply
import com.calypsan.listenup.api.dto.match.PersonMatchReview
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.dto.match.RegionContext
import com.calypsan.listenup.api.dto.match.RegionOrigin
import com.calypsan.listenup.api.dto.match.SearchStep
import com.calypsan.listenup.api.dto.match.SourceStatus
import com.calypsan.listenup.api.dto.match.UndoResult
import com.calypsan.listenup.api.dto.match.YourCopy
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.repository.MatchingRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import kotlinx.coroutines.CompletableDeferred

internal const val BOOK = "book-1"

internal val AUDIBLE = MetadataSource("audible", "Audible")
internal val HARDCOVER = MetadataSource("hardcover", "Hardcover")
internal val ITUNES = MetadataSource("itunes", "iTunes")

internal fun bookDetail(title: String = "Project Hail Mary"): BookDetail =
    BookDetail(
        id = BookId(BOOK),
        libraryId = LibraryId("lib"),
        folderId = FolderId("folder"),
        title = title,
        authors = listOf(BookContributor(id = "a1", name = "Andy Weir")),
        narrators = listOf(BookContributor(id = "n1", name = "Ray Porter")),
        duration = 58_200_000L,
        coverPath = "/covers/phm.jpg",
        addedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
        publishYear = 2021,
    )

internal fun key(
    provider: String,
    id: String,
): BookCandidateKey = BookCandidateKey(listOf(ExternalRef(provider, id)))

internal fun candidate(
    key: BookCandidateKey,
    tier: MatchTier = MatchTier.STRONG,
    isBest: Boolean = false,
    title: String = "Project Hail Mary",
    reasons: List<MatchReason> = listOf(MatchReason.SameNarrator, MatchReason.SameLength),
): BookCandidate =
    BookCandidate(
        key = key,
        title = title,
        subtitle = null,
        authors = listOf("Andy Weir"),
        narrators = listOf("Ray Porter"),
        durationMs = 58_200_000L,
        year = 2021,
        format = null,
        chapterCount = 36,
        coverUrl = null,
        foundIn = listOf(FoundIn(AUDIBLE)),
        tier = tier,
        score = 0.9,
        isBest = isBest,
        isCurrentLink = false,
        reasons = reasons,
    )

internal val BEST = key("audible", "B08G9PRS1K")
internal val HC_ONLY = key("hardcover", "123")
internal val DRAMATIZED = key("audible", "B0DRAMA")

internal fun findResult(
    candidates: List<BookCandidate> =
        listOf(
            candidate(BEST, isBest = true),
            candidate(HC_ONLY),
            candidate(DRAMATIZED, tier = MatchTier.MAYBE, title = "Project Hail Mary [Dramatized Adaptation]"),
        ),
    sources: List<SourceStatus> =
        listOf(SourceStatus.Answered(AUDIBLE, 2), SourceStatus.Answered(HARDCOVER, 1)),
): BookFindResult =
    BookFindResult(
        yourCopy = YourCopy(58_200_000L, listOf("Ray Porter"), 36, 2021, null),
        steps = listOf(SearchStep.TitleAuthorLength),
        candidates = candidates,
        sources = sources,
        region = RegionContext(AUDIBLE, MetadataLocale("us"), RegionOrigin.LIBRARY, MetadataLocale.SUPPORTED),
    )

internal fun text(t: String) = FieldValue.Text(t)

internal fun option(
    id: String,
    value: FieldValue,
    vararg sources: MetadataSource,
) = FieldOption(id, value, sources.toList())

/**
 * The canvas fixture (A-03): description changes (Audible or Hardcover), publisher and release date fill gaps,
 * the title was edited by hand, subtitle is already the same; two covers; genres, moods and 3 chapter names.
 */
internal fun review(
    candidate: BookCandidateKey = BEST,
    revision: Long = 7L,
    descriptionOptions: List<FieldOption> =
        listOf(
            option("audible:d1", text("Ryland Grace…"), AUDIBLE),
            option("hardcover:d2", text("A lone…"), HARDCOVER),
        ),
): BookMatchReview =
    BookMatchReview(
        candidate = candidate,
        region = null,
        basedOnRevision = revision,
        fields =
            listOf(
                FieldReview(
                    field = BookField.DESCRIPTION,
                    current = text("A lone astronaut wakes…"),
                    options = descriptionOptions,
                    defaultChoice = FieldChoice.Option(descriptionOptions.first().optionId),
                    state = FieldState.CHANGES,
                    handEdit = null,
                ),
                FieldReview(
                    BookField.PUBLISHER,
                    null,
                    listOf(option("audible:p", text("Audible Studios"), AUDIBLE)),
                    FieldChoice.Option("audible:p"),
                    FieldState.FILLS_GAP,
                    null,
                ),
                FieldReview(
                    BookField.PUBLISH_YEAR,
                    null,
                    listOf(option("audible:y", FieldValue.Year(2021), AUDIBLE)),
                    FieldChoice.Option("audible:y"),
                    FieldState.FILLS_GAP,
                    null,
                ),
                FieldReview(
                    BookField.TITLE,
                    text("Project Hail Mary"),
                    listOf(option("audible:t", text("Project Hail Mary: A Novel"), AUDIBLE)),
                    FieldChoice.KeepCurrent,
                    FieldState.USER_EDITED,
                    HandEdit(byUserId = "me", byName = "Simon", at = 1_000L),
                ),
                FieldReview(
                    BookField.SUBTITLE,
                    text("A Novel"),
                    listOf(option("audible:s", text("A Novel"), AUDIBLE, HARDCOVER)),
                    FieldChoice.KeepCurrent,
                    FieldState.SAME,
                    null,
                ),
            ),
        cover =
            CoverReview(
                current = CurrentCover(hash = "h", setByHand = false),
                options =
                    listOf(
                        CoverCandidate("audible:c", AUDIBLE, "https://a/c.jpg", 2400, 2400),
                        CoverCandidate("hardcover:c", HARDCOVER, "https://h/c.jpg", 1600, 2400),
                    ),
                defaultChoice = ImageChoice.Candidate("hardcover:c"),
            ),
        genres =
            LabelSetReview(
                yours = listOf("Science Fiction", "Space Opera"),
                suggested =
                    listOf(
                        LabelSuggestion("Hard Science Fiction", listOf(AUDIBLE)),
                        LabelSuggestion("Thriller", listOf(HARDCOVER)),
                    ),
            ),
        moods = LabelSetReview(yours = emptyList(), suggested = listOf(LabelSuggestion("Hopeful", listOf(HARDCOVER)))),
        chapterNames =
            ChapterNamesReview.Available(
                source = AUDIBLE,
                rows = (0 until 3).map { ChapterNameChange(it, "Track 0${it + 1}", "Chapter ${it + 1}") },
                unchangedCount = 33,
            ),
    )

internal fun receipt(id: String = "r-1"): MatchReceipt =
    MatchReceipt(
        receiptId = id,
        appliedAt = 1L,
        changes =
            listOf(
                AppliedChange.Field(BookField.DESCRIPTION, AUDIBLE),
                AppliedChange.Field(BookField.PUBLISHER, AUDIBLE),
                AppliedChange.Field(BookField.PUBLISH_YEAR, AUDIBLE),
                AppliedChange.Genres(added = listOf("Thriller"), removed = emptyList()),
                AppliedChange.Moods(added = listOf("Hopeful"), removed = emptyList()),
                AppliedChange.Cover(HARDCOVER),
                AppliedChange.ChapterNames(16, AUDIBLE),
            ),
        undoable = true,
    )

/** [MatchingRepository] with in-memory state: records every request and answers from settable replies. */
internal class FakeMatchingRepository : MatchingRepository {
    val findRequests = mutableListOf<BookFindRequest>()
    val reviewRequests = mutableListOf<Pair<BookCandidateKey, MetadataLocale?>>()
    val applyRequests = mutableListOf<BookMatchApply>()
    val undoRequests = mutableListOf<String>()

    var findReply: suspend (BookFindRequest) -> AppResult<BookFindResult> = { AppResult.Success(findResult()) }
    var reviewReply: suspend (BookCandidateKey) -> AppResult<BookMatchReview> = { AppResult.Success(review(it)) }
    var applyReply: suspend (BookMatchApply) -> AppResult<MatchReceipt> = { AppResult.Success(receipt()) }
    var undoReply: suspend (String) -> AppResult<UndoResult> = { AppResult.Success(UndoResult(it, emptyList())) }

    val personFindRequests = mutableListOf<PersonFindRequest>()
    val personReviewRequests = mutableListOf<PersonCandidateKey>()
    val personApplyRequests = mutableListOf<PersonMatchApply>()
    var personFindReply: suspend (PersonFindRequest) -> AppResult<PersonFindResult> = {
        AppResult.Success(personFindResult())
    }
    var personReviewReply: suspend (PersonCandidateKey) -> AppResult<PersonMatchReview> = { key ->
        AppResult.Success(personReview(key))
    }
    var personApplyReply: suspend (PersonMatchApply) -> AppResult<MatchReceipt> = { AppResult.Success(personReceipt()) }

    /** When set, Find suspends until completed — to observe the Searching state. */
    var findGate: CompletableDeferred<Unit>? = null

    override suspend fun findBookMatches(
        bookId: BookId,
        request: BookFindRequest,
    ): AppResult<BookFindResult> {
        findRequests += request
        findGate?.await()
        return findReply(request)
    }

    override suspend fun findPeople(
        contributorId: ContributorId,
        request: PersonFindRequest,
    ): AppResult<PersonFindResult> {
        personFindRequests += request
        findGate?.await()
        return personFindReply(request)
    }

    override suspend fun reviewPersonMatch(
        contributorId: ContributorId,
        candidate: PersonCandidateKey,
    ): AppResult<PersonMatchReview> {
        personReviewRequests += candidate
        return personReviewReply(candidate)
    }

    override suspend fun applyPersonMatch(
        contributorId: ContributorId,
        request: PersonMatchApply,
    ): AppResult<MatchReceipt> {
        personApplyRequests += request
        return personApplyReply(request)
    }

    override suspend fun reviewBookMatch(
        bookId: BookId,
        candidate: BookCandidateKey,
        region: MetadataLocale?,
    ): AppResult<BookMatchReview> {
        reviewRequests += candidate to region
        return reviewReply(candidate)
    }

    override suspend fun applyBookMatch(
        bookId: BookId,
        request: BookMatchApply,
    ): AppResult<MatchReceipt> {
        applyRequests += request
        return applyReply(request)
    }

    override suspend fun undoMatch(receiptId: String): AppResult<UndoResult> {
        undoRequests += receiptId
        return undoReply(receiptId)
    }
}
