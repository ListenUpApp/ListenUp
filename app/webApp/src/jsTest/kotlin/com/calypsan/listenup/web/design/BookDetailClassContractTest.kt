package com.calypsan.listenup.web.design

import com.calypsan.listenup.client.presentation.bookedit.BookEditForm
import com.calypsan.listenup.web.editingBook
import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.client.domain.chapter.ChapterAnchor
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.ScoreSource
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditPreviewRow
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.client.presentation.chaptereditor.DriftPreview
import com.calypsan.listenup.client.presentation.chaptereditor.DriftProposal
import com.calypsan.listenup.client.presentation.chaptereditor.DriftRefusal
import com.calypsan.listenup.client.presentation.match.FindFailure
import com.calypsan.listenup.client.presentation.match.MatchReceiptUiState
import com.calypsan.listenup.client.presentation.match.PartialFailure
import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
import com.calypsan.listenup.client.presentation.match.ReviewUiState
import com.calypsan.listenup.web.features.admin.finished
import com.calypsan.listenup.web.features.admin.preview
import com.calypsan.listenup.web.features.bookdetail.BookChaptersSection
import com.calypsan.listenup.web.features.bookdetail.BookDetailPage
import com.calypsan.listenup.web.features.bookdetail.PlacedReaders
import com.calypsan.listenup.web.features.bookdetail.face
import com.calypsan.listenup.web.features.bookdetail.BookFilesPage
import com.calypsan.listenup.web.features.bookdetail.ChapterMapPage
import com.calypsan.listenup.web.features.bookdetail.readyBook
import com.calypsan.listenup.web.features.bookdetail.sampleDocuments
import com.calypsan.listenup.web.features.bookedit.BookEditPage
import com.calypsan.listenup.web.features.bulkedit.BulkEditActions
import com.calypsan.listenup.web.features.bulkedit.BulkEditCatalog
import com.calypsan.listenup.web.features.bulkedit.BulkEditPage
import com.calypsan.listenup.web.features.bulkedit.editing
import com.calypsan.listenup.web.features.chaptereditor.ChapterEditorPage
import com.calypsan.listenup.web.features.chaptereditor.editingChapters
import com.calypsan.listenup.web.features.chaptereditor.threeChapters
import com.calypsan.listenup.web.features.match.AUDIBLE
import com.calypsan.listenup.web.features.match.BookMatchPage
import com.calypsan.listenup.web.features.match.HARDCOVER
import com.calypsan.listenup.web.features.match.MatchReceiptRegion
import com.calypsan.listenup.web.features.match.MatchView
import com.calypsan.listenup.web.features.match.PersonMatchPage
import com.calypsan.listenup.web.features.match.ReceiptSubject
import com.calypsan.listenup.web.features.match.UK
import com.calypsan.listenup.web.features.match.US
import com.calypsan.listenup.web.features.match.biography
import com.calypsan.listenup.web.features.match.candidate as matchCandidate
import com.calypsan.listenup.web.features.match.failed
import com.calypsan.listenup.web.features.match.fixedBookMatch
import com.calypsan.listenup.web.features.match.fixedPersonMatch
import com.calypsan.listenup.web.features.match.noProfiles
import com.calypsan.listenup.web.features.match.person
import com.calypsan.listenup.web.features.match.personFailed
import com.calypsan.listenup.web.features.match.personReady
import com.calypsan.listenup.web.features.match.personReceipt
import com.calypsan.listenup.web.features.match.photo
import com.calypsan.listenup.web.features.match.rayResults
import com.calypsan.listenup.web.features.match.ready
import com.calypsan.listenup.web.features.match.receipt
import com.calypsan.listenup.web.features.match.results
import com.calypsan.listenup.web.features.match.searching
import com.calypsan.listenup.web.features.profile.editing
import com.calypsan.listenup.web.features.ratings.RateBookDialog
import com.calypsan.listenup.web.features.ratings.RatingSourcesDialog
import com.calypsan.listenup.web.features.ratings.RatingsPanel
import com.calypsan.listenup.web.features.readers.ReadersPage
import com.calypsan.listenup.web.features.readers.ReadersPanel
import com.calypsan.listenup.web.features.readers.reader
import com.calypsan.listenup.web.features.readers.readersData
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Every class Book Detail, its match, edit and chapter pages, the readers and the ratings render is defined in the design sheet. A failure names the shape that rendered
 * the undefined class; see [ClassContractTest] for why the contract exists.
 */
class BookDetailClassContractTest :
    FunSpec({
        test(
            "every class Book Detail, its match, edit and chapter pages, the readers and the ratings render is defined in the design sheet",
        ) {
            assertClassesDefined(bookdetailContractShapes())
        }
    })

private fun bookdetailContractShapes(): Map<String, @Composable () -> Unit> =
    bookdetailShapes1() +
        bookdetailShapes2() +
        bookdetailShapes3() +
        bookdetailShapes4() +
        bookdetailShapes5() +
        bookdetailShapes6() +
        readerShapes() +
        ratingShapes()

private fun bookdetailShapes1(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#1: BookDetailPage( state = readyBook( series = listOf(") {
            BookDetailPage(
                state =
                    readyBook(
                        series =
                            listOf(
                                BookSeries(seriesId = "s1", seriesName = "The Stormlight Archive", sequence = 1.0),
                            ),
                        // The chips are the only thing that draws `.bd-facets`, and a
                        // book with genres alone would leave two thirds of them unseen.
                        tags = listOf(Tag(id = "t1", name = "Grimdark", slug = "grimdark")),
                        moods = listOf(Mood(id = "m1", name = "Cosy", slug = "cosy")),
                    ),
                onPlayFromChapter = {},
                onOpenLibrary = {},
                onPlay = {},
                onRetryConnection = {},
            )
        },
        shape("#2: BookDetailPage( state = readyBook( readingStage = Re") {
            // The one page in progress, with readers and a series: the social line, the chapter
            // rows and the series path each draw classes of their own.
            BookDetailPage(
                state =
                    readyBook(
                        readingStage = ReadingStage.InProgress,
                        series =
                            listOf(
                                BookSeries(seriesId = "s1", seriesName = "The Stormlight Archive", sequence = 1.0),
                            ),
                    ),
                onOpenLibrary = {},
                onPlay = {},
                onPlayFromChapter = {},
                readers =
                    readersData(
                        reader(userId = "u1", displayName = "Maya", progressPct = 40),
                        reader(userId = "u2", displayName = "Tom", finishes = listOf(1L)),
                    ),
                onRetryConnection = {},
            )
        },
        shape("#3: ChapterMapPage( state = readyBook(), selection = set") {
            ChapterMapPage(
                state = readyBook(),
                selection = setOf(1, 2),
                onSelectionChange = {},
                onOpenBook = {},
                onOpenLibrary = {},
                onEditChapters = {},
            )
        },
        shape("#4: BookFilesPage( state = readyBook(), documents = samp") {
            BookFilesPage(
                state = readyBook(),
                documents = sampleDocuments(),
                onOpenBook = {},
                onOpenLibrary = {},
            )
        },
        shape("#5: BookDetailPage( state = BookDetailUiState.Error(Book") {
            // The states with no book draw classes of their own, so they belong in
            // the contract too — an invented class hides just as well in an empty
            // state as in a full one.
            BookDetailPage(
                state = BookDetailUiState.Error(BookError.NotFound()),
                onPlayFromChapter = {},
                onOpenLibrary = {},
                onPlay = {},
                onRetryConnection = {},
            )
        },
        shape("#6: BookDetailPage( state = BookDetailUiState.Loading, o") {
            BookDetailPage(
                state = BookDetailUiState.Loading,
                onPlayFromChapter = {},
                onOpenLibrary = {},
                onPlay = {},
                onRetryConnection = {},
            )
        },
    )

private fun bookdetailShapes2(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#7: BulkEditPage( state = editing( bookCount = 39, reque") {
            // Bulk edit: a form with an armed field, a preview carrying a full row, a
            // dimmed row, a not-loaded warning and a notice — plus the resting panel.
            BulkEditPage(
                state =
                    editing(
                        bookCount = 39,
                        requestedCount = 40,
                        edits = listOf(BulkEdit.SetPublisher("Tor")),
                        preview =
                            listOf(
                                BulkEditPreviewRow(BulkEdit.SetPublisher("Tor"), affectedCount = 12),
                                BulkEditPreviewRow(BulkEdit.AddTags(listOf("Grimdark")), affectedCount = 0),
                            ),
                        changedBookCount = 12,
                        sharedPublishYear = 2010,
                    ),
                catalog = contractCatalog,
                actions = contractBulkActions,
                notice = "Stopped after 7 books. The rest were not changed.",
            )
        },
        shape("#8: BulkEditPage( state = editing(bookCount = 40), catal") {
            BulkEditPage(
                state = editing(bookCount = 40),
                catalog = contractCatalog,
                actions = contractBulkActions,
            )
        },
        shape("#9: BulkEditPage( state = BulkEditUiState.Loading, catal") {
            BulkEditPage(
                state = BulkEditUiState.Loading,
                catalog = contractCatalog,
                actions = contractBulkActions,
            )
        },
        shape("#10: BookMatchPage( session = fixedBookMatch( findState =") {
            // Match details: Find with results, a partial banner and Review open with
            // every section; Compare editions; each failure card; the receipt and its
            // settled states.
            BookMatchPage(
                session =
                    fixedBookMatch(
                        findState =
                            MutableStateFlow(
                                results(partialFailure = PartialFailure(listOf(HARDCOVER), listOf(AUDIBLE))),
                            ),
                        reviewState = MutableStateFlow(ready(applyError = MetadataError.CoverDownloadFailed())),
                    ),
                bookId = "b-1",
                viewerId = "u-me",
                view = MatchView.Find,
                onOpenCompare = {},
                onCloseCompare = {},
                onOpenLibrary = {},
                onOpenBook = {},
                onApplied = {},
            )
        },
        shape("#11: BookMatchPage( session = fixedBookMatch(findState =") {
            BookMatchPage(
                session = fixedBookMatch(findState = MutableStateFlow(searching(previous = results()))),
                bookId = "b-1",
                viewerId = null,
                view = MatchView.Compare,
                onOpenCompare = {},
                onCloseCompare = {},
                onOpenLibrary = {},
                onOpenBook = {},
                onApplied = {},
            )
        },
        shape("#12: BookMatchPage( session = fixedBookMatch( findState =") {
            BookMatchPage(
                session =
                    fixedBookMatch(
                        findState = MutableStateFlow(failed(FindFailure.NotFoundInStore(AUDIBLE, UK, listOf(US)))),
                        reviewState = MutableStateFlow(ReviewUiState.Loading(matchCandidate())),
                    ),
                bookId = "b-1",
                viewerId = null,
                view = MatchView.Find,
                onOpenCompare = {},
                onCloseCompare = {},
                onOpenLibrary = {},
                onOpenBook = {},
                onApplied = {},
            )
        },
    )

private fun bookdetailShapes3(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#13: BookMatchPage( session = fixedBookMatch( findState =") {
            BookMatchPage(
                session =
                    fixedBookMatch(
                        findState = MutableStateFlow(failed(FindFailure.RateLimited(HARDCOVER, 30))),
                        reviewState =
                            MutableStateFlow(ReviewUiState.Failed(matchCandidate(), MetadataError.Malformed())),
                    ),
                bookId = "b-1",
                viewerId = null,
                view = MatchView.Find,
                onOpenCompare = {},
                onCloseCompare = {},
                onOpenLibrary = {},
                onOpenBook = {},
                onApplied = {},
            )
        },
        shape("#14: MatchReceiptRegion( state = MatchReceiptUiState.Show") {
            MatchReceiptRegion(
                state = MatchReceiptUiState.Shown(receipt(), undoing = false, undoError = null),
                onUndo = {},
                onDismiss = {},
            )
        },
        shape("#15: MatchReceiptRegion(state = MatchReceiptUiState.Undon") {
            MatchReceiptRegion(state = MatchReceiptUiState.Undone, onUndo = {}, onDismiss = {})
        },
        shape("#16: PersonMatchPage( session = fixedPersonMatch( findSta") {
            // Person Match details: Find with every role's evidence on the strip and the rows,
            // Review with a hand-edited biography; No profiles; a failure with Review loading;
            // the person receipt.
            PersonMatchPage(
                session =
                    fixedPersonMatch(
                        findState = MutableStateFlow(rayResults()),
                        reviewState =
                            MutableStateFlow(
                                personReady(
                                    photo = photo(currentPath = "p.jpg", setByHand = true),
                                    biography = biography(state = FieldState.USER_EDITED),
                                    applyError = MetadataError.CoverDownloadFailed(),
                                ),
                            ),
                    ),
                contributorId = "c-1",
                viewerId = "u-me",
                onOpenLibrary = {},
                onOpenContributor = {},
                onEditByHand = {},
                onApplied = {},
            )
        },
        shape("#17: PersonMatchPage( session = fixedPersonMatch( findSta") {
            PersonMatchPage(
                session =
                    fixedPersonMatch(
                        findState = MutableStateFlow(noProfiles()),
                        reviewState = MutableStateFlow(PersonReviewUiState.Loading(person())),
                    ),
                contributorId = "c-1",
                viewerId = null,
                onOpenLibrary = {},
                onOpenContributor = {},
                onEditByHand = {},
                onApplied = {},
            )
        },
        shape("#18: PersonMatchPage( session = fixedPersonMatch( findSta") {
            PersonMatchPage(
                session =
                    fixedPersonMatch(
                        findState = MutableStateFlow(personFailed(FindFailure.Offline)),
                        reviewState = MutableStateFlow(PersonReviewUiState.Failed(person(), MetadataError.Malformed())),
                    ),
                contributorId = "c-1",
                viewerId = null,
                onOpenLibrary = {},
                onOpenContributor = {},
                onEditByHand = {},
                onApplied = {},
            )
        },
    )

private fun bookdetailShapes4(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#19: MatchReceiptRegion( state = MatchReceiptUiState.Show") {
            MatchReceiptRegion(
                state = MatchReceiptUiState.Shown(personReceipt(), undoing = false, undoError = null),
                onUndo = {},
                onDismiss = {},
                subject = ReceiptSubject.Person("Ray Porter"),
            )
        },
        shape("#20: ChapterEditorPage( state = editingChapters( selected") {
            // Chapter Editor: the list with a locked row, an unsaved draft, a
            // changed-elsewhere banner and a refused save; the drift panel in each of
            // its three shapes (nothing pinned, ready, refused); and the two states
            // with no list — the empty book and the skeleton.
            ChapterEditorPage(
                state =
                    editingChapters(
                        selectedChapterId = "c2",
                        isDirty = true,
                        canUndo = true,
                        changedElsewhere = true,
                        lockedChapterIds = setOf("c1"),
                    ),
                playheadMs = 61_500L,
                onSelect = {},
                onNudge = { _, _ -> },
                onSnapToPlayhead = { _, _ -> },
                onRetitle = { _, _ -> },
                onRemove = {},
                onAddAt = { _, _ -> },
                onRetime = { _, _ -> },
                onInsertBelow = { _, _ -> },
                onPlayFrom = {},
                onToggleLock = {},
                onBeginDrift = {},
                onPinAnchor = { _, _ -> },
                onApplyDrift = {},
                onCancelDrift = {},
                onUndo = {},
                onSave = {},
                onLeave = {},
                problem = "Chapter 2 needs a title.",
            )
        },
        shape("#21: ChapterEditorPage( state = editingChapters( selected") {
            ChapterEditorPage(
                state =
                    editingChapters(
                        selectedChapterId = "c1",
                        drift =
                            ChapterEditorUiState.DriftState(
                                proposal = DriftProposal(first = ChapterAnchor("c1", 0L)),
                                preview =
                                    DriftPreview.Ready(
                                        corrected = threeChapters(),
                                        affectedCount = 3,
                                        firstOffsetMs = 0L,
                                        lastOffsetMs = 1_000L,
                                    ),
                            ),
                    ),
                playheadMs = 1_000L,
                onSelect = {},
                onNudge = { _, _ -> },
                onSnapToPlayhead = { _, _ -> },
                onRetitle = { _, _ -> },
                onRemove = {},
                onAddAt = { _, _ -> },
                onRetime = { _, _ -> },
                onInsertBelow = { _, _ -> },
                onPlayFrom = {},
                onToggleLock = {},
                onBeginDrift = {},
                onPinAnchor = { _, _ -> },
                onApplyDrift = {},
                onCancelDrift = {},
                onUndo = {},
                onSave = {},
                onLeave = {},
            )
        },
    )

private fun bookdetailShapes5(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#22: ChapterEditorPage( state = editingChapters( drift =") {
            ChapterEditorPage(
                state =
                    editingChapters(
                        drift =
                            ChapterEditorUiState.DriftState(
                                proposal = DriftProposal(first = ChapterAnchor("c1", 0L)),
                                preview = DriftPreview.Refused(DriftRefusal.InvertedAnchors),
                            ),
                    ),
                playheadMs = null,
                onSelect = {},
                onNudge = { _, _ -> },
                onSnapToPlayhead = { _, _ -> },
                onRetitle = { _, _ -> },
                onRemove = {},
                onAddAt = { _, _ -> },
                onRetime = { _, _ -> },
                onInsertBelow = { _, _ -> },
                onPlayFrom = {},
                onToggleLock = {},
                onBeginDrift = {},
                onPinAnchor = { _, _ -> },
                onApplyDrift = {},
                onCancelDrift = {},
                onUndo = {},
                onSave = {},
                onLeave = {},
            )
        },
        shape("#23: ChapterEditorPage( state = editingChapters(chapters") {
            ChapterEditorPage(
                state = editingChapters(chapters = emptyList()),
                playheadMs = 1_000L,
                onSelect = {},
                onNudge = { _, _ -> },
                onSnapToPlayhead = { _, _ -> },
                onRetitle = { _, _ -> },
                onRemove = {},
                onAddAt = { _, _ -> },
                onRetime = { _, _ -> },
                onInsertBelow = { _, _ -> },
                onPlayFrom = {},
                onToggleLock = {},
                onBeginDrift = {},
                onPinAnchor = { _, _ -> },
                onApplyDrift = {},
                onCancelDrift = {},
                onUndo = {},
                onSave = {},
                onLeave = {},
            )
        },
        shape("#24: ChapterEditorPage( state = ChapterEditorUiState.Load") {
            ChapterEditorPage(
                state = ChapterEditorUiState.Loading,
                playheadMs = null,
                onSelect = {},
                onNudge = { _, _ -> },
                onSnapToPlayhead = { _, _ -> },
                onRetitle = { _, _ -> },
                onRemove = {},
                onAddAt = { _, _ -> },
                onRetime = { _, _ -> },
                onInsertBelow = { _, _ -> },
                onPlayFrom = {},
                onToggleLock = {},
                onBeginDrift = {},
                onPinAnchor = { _, _ -> },
                onApplyDrift = {},
                onCancelDrift = {},
                onUndo = {},
                onSave = {},
                onLeave = {},
            )
        },
    )

private fun bookdetailShapes6(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#25: BookEditPage( state = BookEditUiState( isLoading = f") {
            // Book Edit was absent from this contract, which is how its form wrapper
            // shipped a class the stylesheet had never heard of. The loaded page
            // exercises every field primitive it owns.
            BookEditPage(
                state =
                    editingBook(
                        BookEditForm(
                            bookId = "b1",
                            title = "The Institute",
                            publisher = "Hodder",
                            language = "en",
                        ),
                    ),
                onEvent = {},
                onOpenLibrary = {},
                onOpenBook = {},
            )
        },
    )

/**
 * The Readers panel and page. The panel is included in its *populated* forms only — its Loading,
 * Error and empty shapes deliberately draw nothing, so they carry no classes to contract.
 */
private fun readerShapes(): Map<String, @Composable () -> Unit> {
    val now = 1_800_000_000_000L
    val reading = reader(userId = "u1", displayName = "Ada Lovelace", progressPct = 42)
    val finished = reader(userId = "u2", displayName = "Grace Hopper", finishes = listOf(now - 86_400_000L))
    // Seven, so the capped panel draws its "See all" — the only shape that does.
    val many = (1..7).map { reader(userId = "u$it", displayName = "Reader $it", progressPct = it) }

    fun panel(state: BookReadersUiState): @Composable () -> Unit =
        { ReadersPanel(state = state, nowMs = now, onOpenProfile = {}, onSeeAll = {}) }

    fun page(state: BookReadersUiState): @Composable () -> Unit =
        {
            ReadersPage(
                state = state,
                bookTitle = "The Way of Kings",
                nowMs = now,
                onOpenProfile = {},
                onOpenBook = {},
            )
        }

    return shapes(
        "reader #1: panel(readersData(reading, finished))" to panel(readersData(reading, finished)),
        "reader #2: panel(readersData(*many.toTypedArray()))" to panel(readersData(*many.toTypedArray())),
        "reader #3: page(readersData(reading, finished))" to page(readersData(reading, finished)),
        "reader #4: page(BookReadersUiState.Loading)" to page(BookReadersUiState.Loading),
        "reader #5: page(BookReadersUiState.NoReaders)" to page(BookReadersUiState.NoReaders),
        "reader #6: page(BookReadersUiState.Error(isRetryabl" to page(BookReadersUiState.Error(isRetryable = true)),
        // Another reader listening now: the panel's live ring.
        "reader #7: panel(live other reader)" to
            panel(BookReadersUiState.Data(BookReaders(listOf(reading)), liveUserIds = setOf(reading.userId))),
        // The chapter list with readers on it: a live face and its glow, a +N disc, and "Show all"'s stack.
        "reader #8: chapters with readers" to { chaptersWithReaders() },
    )
}

@Composable
private fun chaptersWithReaders() {
    val chapters =
        (0 until 8).map { i ->
            ChapterUiModel("c$i", "Chapter ${i + 1}", "10:00", null, i == 0, i * 60_000L, 60_000L)
        }
    val crowd = listOf(face("Maya", isLive = true), face("Priya"), face("Tom"), face("Sam"))
    BookChaptersSection(
        bookId = "42",
        chapters = chapters,
        preview = chapters.take(5),
        canPlay = true,
        wide = false,
        onPlayFromChapter = {},
        onOpenChapterMap = {},
        readers = PlacedReaders(mapOf("c1" to crowd, "c7" to listOf(face("Lee")))),
    )
}

/** Nothing to offer: the contract renders the fields' shapes, not their pickers' contents. */
private val contractCatalog =
    BulkEditCatalog(
        genres = emptyList(),
        tags = emptyList(),
        moods = emptyList(),
        seriesMatches = emptyList(),
        contributorMatches = emptyList(),
    )

private val contractBulkActions =
    BulkEditActions(
        onSeriesQuery = {},
        onContributorQuery = {},
        onPublisher = {},
        onYear = {},
        onLanguage = {},
        onSeries = {},
        onContributors = {},
        onGenres = {},
        onTags = {},
        onMoods = {},
        onApply = {},
        onLeave = {},
    )

/**
 * The rating panel in both of its drawn forms, the rate dialog with a rating to remove, and a
 * reader line carrying stars and a note. Loading draws nothing, so it carries no classes.
 */
private fun ratingShapes(): Map<String, @Composable () -> Unit> {
    val mine = ListenerRating(bookId = "b1", userId = "me", halfStars = 7, note = "Loved it", ratedAtMs = 0L)
    val rated =
        BookRatingsUiState.Ready(
            listeners = ListenerAverage(averageHalfStars = 8.0, count = 3),
            mine = mine,
            external = null,
            breakdown = emptyList(),
            canRefresh = false,
        )
    val ratedReader = reader(userId = "me", displayName = "Ada Lovelace", rating = mine)
    val sourcesScore =
        CombinedScore(
            average = 4.6,
            count = 12_203,
            shares =
                mapOf(
                    ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 0.92,
                    ScoreSource.Listeners to 0.08,
                ),
        )
    val sourcesBreakdown = listOf(ExternalRating(ExternalRatingSource.AUDIBLE, 4.8, 11_000, fetchedAtMs = 1L))
    return shapes(
        shape("rating #1: { RatingsPanel(state = rated, onSetStars") {
            RatingsPanel(state = rated, onSetStars = {}, onRate = { _, _ -> }, onClear = {})
        },
        shape("rating #2: { RatingsPanel( state = BookRatingsUiSta") {
            RatingsPanel(
                state =
                    BookRatingsUiState.Ready(
                        listeners = null,
                        mine = null,
                        external = null,
                        breakdown = emptyList(),
                        canRefresh = true,
                    ),
                onSetStars = {},
                onRate = { _, _ -> },
                onClear = {},
            )
        },
        shape("rating #3: { RatingsPanel( state = BookRatingsUiSta") {
            RatingsPanel(
                state =
                    BookRatingsUiState.Ready(
                        listeners = ListenerAverage(9.0, 1),
                        mine = mine,
                        external = null,
                        breakdown = emptyList(),
                        canRefresh = false,
                        isCheckingExternal = true,
                    ),
                onSetStars = {},
                onRate = { _, _ -> },
                onClear = {},
            )
        },
        shape("rating #4: { RatingsPanel( state = BookRatingsUiSta") {
            RatingsPanel(
                state =
                    BookRatingsUiState.Ready(
                        listeners = ListenerAverage(8.0, 3),
                        mine = null,
                        external = sourcesScore,
                        breakdown = sourcesBreakdown,
                        canRefresh = false,
                    ),
                onSetStars = {},
                onRate = { _, _ -> },
                onClear = {},
            )
        },
        shape("rating #5: { RatingSourcesDialog( open = true, read") {
            RatingSourcesDialog(
                open = true,
                ready =
                    BookRatingsUiState.Ready(
                        listeners = ListenerAverage(8.0, 3),
                        mine = mine,
                        external = sourcesScore,
                        breakdown = sourcesBreakdown,
                        canRefresh = true,
                    ),
                nowMs = 86_400_000L * 3,
                onRefresh = {},
                onDismiss = {},
            )
        },
        shape("rating #6: { RateBookDialog(open = true, current =") {
            RateBookDialog(open = true, current = mine, onSave = { _, _ -> }, onClear = {}, onDismiss = {})
        },
        shape("rating #7: { ReadersPanel(state = readersData(rated") {
            ReadersPanel(state = readersData(ratedReader), nowMs = 0L, onOpenProfile = {}, onSeeAll = {})
        },
    )
}
