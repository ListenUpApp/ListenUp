package com.calypsan.listenup.web.design

import com.calypsan.listenup.api.error.ValidationError
import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.ShelfBook
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetUiState
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.contributoredit.MAX_MERGE_CANDIDATES as CONTRIBUTOR_MERGE_CAP
import com.calypsan.listenup.client.presentation.genredestination.GenreCrumb
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationUiState
import com.calypsan.listenup.client.presentation.genredestination.SubGenre
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.merge.MergeHistoryState
import com.calypsan.listenup.client.presentation.search.SearchUiState
import com.calypsan.listenup.client.presentation.search.SeeAllSearchUiState
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.presentation.seriesedit.MAX_MERGE_CANDIDATES
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfUiState
import com.calypsan.listenup.client.presentation.shelf.ShelfDetailUiState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.web.features.admin.finished
import com.calypsan.listenup.web.features.admin.genre
import com.calypsan.listenup.web.features.admin.preview
import com.calypsan.listenup.web.features.browse.BrowseFacetPage
import com.calypsan.listenup.web.features.browse.GenreDestinationPage
import com.calypsan.listenup.web.features.browse.facetReady
import com.calypsan.listenup.web.features.browse.genreReady
import com.calypsan.listenup.web.features.contributordetail.ContributorDetailPage
import com.calypsan.listenup.web.features.contributordetail.bookItem
import com.calypsan.listenup.web.features.contributordetail.readyContributor
import com.calypsan.listenup.web.features.contributordetail.roleSection
import com.calypsan.listenup.web.features.contributordetail.seriesWithBooks
import com.calypsan.listenup.web.features.contributoredit.ContributorEditPage
import com.calypsan.listenup.web.features.contributoredit.candidate
import com.calypsan.listenup.web.features.contributoredit.editingContributor
import com.calypsan.listenup.web.features.contributors.ContributorsPage
import com.calypsan.listenup.web.features.contributors.contributor
import com.calypsan.listenup.web.features.home.scanning
import com.calypsan.listenup.web.features.home.shelf
import com.calypsan.listenup.web.features.library.LibraryPage
import com.calypsan.listenup.web.features.library.contractBook
import com.calypsan.listenup.web.features.library.contractLibrary
import com.calypsan.listenup.web.features.search.CommandPalette
import com.calypsan.listenup.web.features.search.ResultsList
import com.calypsan.listenup.web.features.search.SearchPage
import com.calypsan.listenup.web.features.search.SeeAllPage
import com.calypsan.listenup.web.features.search.bookHit
import com.calypsan.listenup.web.features.search.contributorHit
import com.calypsan.listenup.web.features.search.searchResult
import com.calypsan.listenup.web.features.seriesdetail.SeriesDetailPage
import com.calypsan.listenup.web.features.seriesdetail.readySeries
import com.calypsan.listenup.web.features.seriesdetail.seriesBook
import com.calypsan.listenup.web.features.seriesedit.SeriesEditPage
import com.calypsan.listenup.web.features.seriesedit.editingSeries
import com.calypsan.listenup.web.features.seriesedit.seriesCandidate
import com.calypsan.listenup.web.features.shelf.ShelfDetailPage
import com.calypsan.listenup.web.features.shelf.ShelfEditPage
import io.kotest.core.spec.style.FunSpec

/**
 * Every class Library, contributors, series, search and the command palette render is defined in the design sheet. A failure names the shape that rendered
 * the undefined class; see [ClassContractTest] for why the contract exists.
 */
class LibraryClassContractTest :
    FunSpec({
        test("every class Library, contributors, series, search and the command palette render is defined in the design sheet") {
            assertClassesDefined(libraryContractShapes())
        }
    })

private fun libraryContractShapes(): Map<String, @Composable () -> Unit> =
    libraryShapes1() +
        libraryShapes2() +
        libraryShapes3() +
        libraryShapes4() +
        libraryShapes5() +
        libraryShapes6() +
        shelfShapes() +
        browseShapes() +
        seeAllShapes()

private fun libraryShapes1(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#1: LibraryPage( state = contractLibrary(books = listOf(") {
            // Every Library state draws classes of its own — the grid and sort row
            // from a loaded page, and the two empty states, which are the ones most
            // likely to be styled by eye and never looked at again.
            LibraryPage(
                state = contractLibrary(books = listOf(contractBook("b1", "Dune"))),
                onEvent = {},
                onOpenBook = {},
                onSelectFacet = {},
            )
        },
        shape("#2: LibraryPage( state = contractLibrary(syncing = true)") {
            LibraryPage(
                state = contractLibrary(syncing = true),
                onEvent = {},
                onOpenBook = {},
                onSelectFacet = {},
            )
        },
        shape("#3: LibraryPage(state = contractLibrary(), onEvent = {},") {
            LibraryPage(state = contractLibrary(), onEvent = {}, onOpenBook = {}, onSelectFacet = {})
        },
        shape("#4: LibraryPage(state = LibraryUiState.Loading, onEvent") {
            LibraryPage(state = LibraryUiState.Loading, onEvent = {}, onOpenBook = {}, onSelectFacet = {})
        },
        shape("#5: LibraryPage(state = LibraryUiState.Error(nope), onEv") {
            // The error state's Retry, and a live scan's strip above the grid.
            LibraryPage(state = LibraryUiState.Error("nope"), onEvent = {}, onOpenBook = {}, onSelectFacet = {})
        },
        shape("#6: LibraryPage( state = contractLibrary(books = listOf(") {
            LibraryPage(
                state =
                    contractLibrary(books = listOf(contractBook("b1", "Dune")))
                        .copy(isServerScanning = true, scanProgress = scanning()),
                onEvent = {},
                onOpenBook = {},
                onSelectFacet = {},
            )
        },
        shape("#7: ContributorsPage( state = listOf(contributor(c1, And") {
            // Every Contributors state: a populated author list, a populated narrator
            // list (so `.contrib-role-chip.is-narrator` actually renders — an empty
            // list here would exercise no row at all), the empty state, and the null
            // loading state. The page joins this contract by hand — the render list
            // below is explicit, so a page nobody adds here is a page whose invented
            // classes nothing catches (which is exactly how `.contrib-list` shipped
            // undefined).
            ContributorsPage(
                state = listOf(contributor("c1", "Andy Weir", 3)),
                role = ContributorRole.AUTHOR,
                onSelectFacet = {},
                onOpenContributor = {},
            )
        },
        shape("#8: ContributorsPage( state = listOf(contributor(c2, San") {
            ContributorsPage(
                state = listOf(contributor("c2", "Santino Fontana", 2)),
                role = ContributorRole.NARRATOR,
                onSelectFacet = {},
                onOpenContributor = {},
            )
        },
        shape("#9: ContributorsPage( state = emptyList(), role = Contri") {
            ContributorsPage(
                state = emptyList(),
                role = ContributorRole.NARRATOR,
                onSelectFacet = {},
                onOpenContributor = {},
            )
        },
        shape("#10: ContributorsPage( state = null, role = ContributorRo") {
            ContributorsPage(
                state = null,
                role = ContributorRole.AUTHOR,
                onSelectFacet = {},
                onOpenContributor = {},
            )
        },
    )

private fun libraryShapes2(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#11: ContributorDetailPage( onAdjustPhoto = {}, state = r") {
            // Contributor Detail: a fully loaded page (role panels, a credited-as alias,
            // a series panel) and every non-Ready state — each draws classes of its own,
            // and a state nobody renders here is one whose invented classes nothing catches.
            ContributorDetailPage(
                onAdjustPhoto = {},
                state =
                    readyContributor(
                        roleSections =
                            listOf(
                                roleSection(
                                    displayName = "Written By",
                                    bookCount = 58,
                                    previewBooks = listOf(bookItem("b1", "The Institute")),
                                ),
                            ),
                        bookCreditedAs = mapOf("b1" to "Richard Bachman"),
                        series = listOf(seriesWithBooks()),
                    ),
                onOpenLibrary = {},
                onOpenContributors = {},
                onOpenBook = {},
                onConfirmDelete = {},
                onDismissDeleteError = {},
            )
        },
        shape("#12: ContributorDetailPage( onAdjustPhoto = {}, state = C") {
            ContributorDetailPage(
                onAdjustPhoto = {},
                state = ContributorDetailUiState.Loading,
                onOpenLibrary = {},
                onOpenContributors = {},
                onOpenBook = {},
                onConfirmDelete = {},
                onDismissDeleteError = {},
            )
        },
        shape("#13: ContributorDetailPage( onAdjustPhoto = {}, state = C") {
            ContributorDetailPage(
                onAdjustPhoto = {},
                state = ContributorDetailUiState.Error("The server could not be reached."),
                onOpenLibrary = {},
                onOpenContributors = {},
                onOpenBook = {},
                onConfirmDelete = {},
                onDismissDeleteError = {},
            )
        },
        shape("#14: ContributorDetailPage( onAdjustPhoto = {}, state = C") {
            ContributorDetailPage(
                onAdjustPhoto = {},
                state = ContributorDetailUiState.NotFound,
                onOpenLibrary = {},
                onOpenContributors = {},
                onOpenBook = {},
                onConfirmDelete = {},
                onDismissDeleteError = {},
            )
        },
        shape("#15: ContributorEditPage( state = editingContributor( err") {
            // Contributor Edit: the loaded form with everything on it (an error banner,
            // a portrait, aliases), the skeleton, and each dialog — three dialogs that
            // never appear together, so each needs its own render or its classes are
            // invented in a file nothing renders.
            ContributorEditPage(
                state =
                    editingContributor(
                        error = ValidationError(message = "That name is already taken."),
                        aliases = listOf("Richard Bachman"),
                        imagePath = "contributors/c-king.jpg",
                    ),
                mergeCandidates = emptyList(),
                onEvent = {},
                onMergeQuery = {},
            )
        },
        shape("#16: ContributorEditPage( state = editingContributor(isLo") {
            ContributorEditPage(
                state = editingContributor(isLoading = true),
                mergeCandidates = emptyList(),
                onEvent = {},
                onMergeQuery = {},
            )
        },
    )

private fun libraryShapes3(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#17: ContributorEditPage( state = editingContributor(merg") {
            ContributorEditPage(
                state = editingContributor(mergeDialogVisible = true, mergeQuery = "Bach"),
                mergeCandidates = listOf(candidate()),
                onEvent = {},
                onMergeQuery = {},
            )
        },
        shape("#18: ContributorEditPage( state = editingContributor(rena") {
            ContributorEditPage(
                state = editingContributor(renameCollisionCandidate = candidate()),
                mergeCandidates = emptyList(),
                onEvent = {},
                onMergeQuery = {},
            )
        },
        shape("#19: SeriesEditPage( state = editingSeries(error = That n") {
            // Series Edit: the loaded form with an error banner and its own artwork,
            // the same form with a staged pick (which swaps the art for a preview and
            // adds the discard control), the skeleton, and the picker in each of its
            // three shapes — nothing matched, a selectable list, and a capped one.
            SeriesEditPage(
                state = editingSeries(error = ValidationError(message = "That name is taken."), coverPath = "series/s.jpg"),
                mergeCandidates = emptyList(),
                mergeHistory = contractMergeHistory,
                onEvent = {},
                onMergeQuery = {},
            )
        },
        shape("#20: SeriesEditPage( state = stagedCoverSeries, mergeCand") {
            SeriesEditPage(
                state = stagedCoverSeries,
                mergeCandidates = emptyList(),
                mergeHistory = MergeHistoryState.Unavailable(TransportError.NetworkUnavailable()),
                onEvent = {},
                onMergeQuery = {},
            )
        },
        shape("#21: SeriesEditPage( state = editingSeries(isLoading = tr") {
            SeriesEditPage(
                state = editingSeries(isLoading = true),
                mergeCandidates = emptyList(),
                mergeHistory = MergeHistoryState.Loading,
                onEvent = {},
                onMergeQuery = {},
            )
        },
        shape("#22: SeriesEditPage( state = editingSeries(mergeDialogVis") {
            SeriesEditPage(
                state = editingSeries(mergeDialogVisible = true, mergeQuery = "Nothing"),
                mergeCandidates = emptyList(),
                mergeHistory = MergeHistoryState.Ready(emptyList()),
                onEvent = {},
                onMergeQuery = {},
            )
        },
        shape("#23: SeriesEditPage( state = editingSeries(mergeDialogVis") {
            SeriesEditPage(
                state = editingSeries(mergeDialogVisible = true, mergeQuery = "Mist"),
                mergeCandidates =
                    (1..MAX_MERGE_CANDIDATES).map {
                        seriesCandidate(id = "s$it", displayName = "Series $it")
                    },
                mergeHistory = MergeHistoryState.Ready(emptyList()),
                onEvent = {},
                onMergeQuery = {},
            )
        },
        shape("#24: ContributorEditPage( state = editingContributor(merg") {
            // And the contributor picker with a full page, for the truncation notice.
            ContributorEditPage(
                state = editingContributor(mergeDialogVisible = true, mergeQuery = "B"),
                mergeCandidates =
                    (1..CONTRIBUTOR_MERGE_CAP).map {
                        candidate(id = "c$it", displayName = "Person $it")
                    },
                onEvent = {},
                onMergeQuery = {},
            )
        },
    )

private fun libraryShapes4(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#25: SeriesDetailPage( state = readySeries( seriesDescrip") {
            // Series Detail: a fully loaded page — hero stats, the reading order with a
            // progress bar and a finished mark, and the About panel — plus the two
            // states with no series, which draw classes of their own.
            SeriesDetailPage(
                state =
                    readySeries(
                        seriesDescription = "Ten orders of Knights Radiant.",
                        books =
                            listOf(
                                seriesBook("b1", "The Way of Kings", 1.0),
                                seriesBook("b2", "Words of Radiance", 2.0),
                            ),
                        bookProgress = mapOf(BookId("b2") to 0.4f),
                        finishedBookIds = setOf(BookId("b1")),
                        resumeTarget = BookId("b2"),
                    ),
                onOpenLibrary = {},
                onOpenBook = {},
            )
        },
        shape("#26: SeriesDetailPage( state = SeriesDetailUiState.Error(") {
            SeriesDetailPage(
                state = SeriesDetailUiState.Error("Series not found"),
                onOpenLibrary = {},
                onOpenBook = {},
            )
        },
        shape("#27: SeriesDetailPage( state = SeriesDetailUiState.Loadin") {
            SeriesDetailPage(
                state = SeriesDetailUiState.Loading,
                onOpenLibrary = {},
                onOpenBook = {},
            )
        },
        shape("#28: SearchPage( state = SearchUiState.Idle(), onQueryCha") {
            // Every SearchUiState variant: Idle, TooShort, Searching, Error, a
            // zero-hit Results and a populated one. The page joins this contract by
            // hand, same as ContributorsPage above — a state nobody adds here is a
            // state whose invented classes nothing catches.
            SearchPage(
                state = SearchUiState.Idle(),
                onQueryChanged = {},
                onToggleType = {},
                onClearTypes = {},
                onOpenHit = {},
                onRetry = {},
                openableTypes = SearchHitType.entries.toSet(),
            )
        },
        shape("#29: SearchPage( state = SearchUiState.TooShort(query = d") {
            SearchPage(
                state = SearchUiState.TooShort(query = "du", selectedTypes = emptySet()),
                onQueryChanged = {},
                onToggleType = {},
                onClearTypes = {},
                onOpenHit = {},
                onRetry = {},
                openableTypes = SearchHitType.entries.toSet(),
            )
        },
        shape("#30: SearchPage( state = SearchUiState.Searching(query =") {
            SearchPage(
                state = SearchUiState.Searching(query = "dun", selectedTypes = emptySet()),
                onQueryChanged = {},
                onToggleType = {},
                onClearTypes = {},
                onOpenHit = {},
                onRetry = {},
                openableTypes = SearchHitType.entries.toSet(),
            )
        },
        shape("#31: SearchPage( state = SearchUiState.Error(query = dune") {
            SearchPage(
                state = SearchUiState.Error(query = "dune", selectedTypes = emptySet(), message = "oops"),
                onQueryChanged = {},
                onToggleType = {},
                onClearTypes = {},
                onOpenHit = {},
                onRetry = {},
                openableTypes = SearchHitType.entries.toSet(),
            )
        },
    )

private fun libraryShapes5(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#32: SearchPage( state = SearchUiState.Results( query = z") {
            SearchPage(
                state =
                    SearchUiState.Results(
                        query = "zzzzz",
                        selectedTypes = emptySet(),
                        result = searchResult(query = "zzzzz", hits = emptyList()),
                    ),
                onQueryChanged = {},
                onToggleType = {},
                onClearTypes = {},
                onOpenHit = {},
                onRetry = {},
                openableTypes = SearchHitType.entries.toSet(),
            )
        },
        shape("#33: SearchPage( state = SearchUiState.Results( query = d") {
            // A book hit (openable — real chevron and button semantics) alongside a
            // contributor hit (not in openableTypes — exercises `.is-static`, the
            // class a hit type with no destination renders instead of a dead click).
            SearchPage(
                state =
                    SearchUiState.Results(
                        query = "dune",
                        selectedTypes = emptySet(),
                        result =
                            searchResult(
                                query = "dune",
                                hits =
                                    listOf(
                                        bookHit("b1", "Dune", author = "Frank Herbert"),
                                        contributorHit("c1", "Frank Herbert"),
                                    ),
                            ),
                    ),
                onQueryChanged = {},
                onToggleType = {},
                onClearTypes = {},
                onOpenHit = {},
                onRetry = {},
                openableTypes = setOf(SearchHitType.BOOK),
            )
        },
        shape("#34: CommandPalette( state = SearchUiState.Idle(), onQuer") {
            // The command palette's own compact render of the same five
            // SearchUiState cases, plus the highlighted-row state SearchPage never
            // sets — every one of them joins this contract by hand, same as
            // SearchPage's block above.
            CommandPalette(
                state = SearchUiState.Idle(),
                onQueryChanged = {},
                onOpenHit = {},
                openableTypes = SearchHitType.entries.toSet(),
                highlighted = null,
                onDismiss = {},
            )
        },
        shape("#35: CommandPalette( state = SearchUiState.TooShort(query") {
            CommandPalette(
                state = SearchUiState.TooShort(query = "du", selectedTypes = emptySet()),
                onQueryChanged = {},
                onOpenHit = {},
                openableTypes = SearchHitType.entries.toSet(),
                highlighted = null,
                onDismiss = {},
            )
        },
        shape("#36: CommandPalette( state = SearchUiState.Searching(quer") {
            CommandPalette(
                state = SearchUiState.Searching(query = "dun", selectedTypes = emptySet()),
                onQueryChanged = {},
                onOpenHit = {},
                openableTypes = SearchHitType.entries.toSet(),
                highlighted = null,
                onDismiss = {},
            )
        },
        shape("#37: CommandPalette( state = SearchUiState.Error(query =") {
            CommandPalette(
                state = SearchUiState.Error(query = "dune", selectedTypes = emptySet(), message = "oops"),
                onQueryChanged = {},
                onOpenHit = {},
                openableTypes = SearchHitType.entries.toSet(),
                highlighted = null,
                onDismiss = {},
            )
        },
    )

private fun libraryShapes6(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#38: CommandPalette( state = SearchUiState.Results( query") {
            CommandPalette(
                state =
                    SearchUiState.Results(
                        query = "zzzzz",
                        selectedTypes = emptySet(),
                        result = searchResult(query = "zzzzz", hits = emptyList()),
                    ),
                onQueryChanged = {},
                onOpenHit = {},
                openableTypes = SearchHitType.entries.toSet(),
                highlighted = null,
                onDismiss = {},
            )
        },
        shape("#39: run { val hit = bookHit(b1, Dune, author = Frank Her") {
            run {
                val hit = bookHit("b1", "Dune", author = "Frank Herbert")
                CommandPalette(
                    state =
                        SearchUiState.Results(
                            query = "dune",
                            selectedTypes = emptySet(),
                            result =
                                searchResult(
                                    query = "dune",
                                    hits = listOf(hit, contributorHit("c1", "Frank Herbert")),
                                ),
                        ),
                    onQueryChanged = {},
                    onOpenHit = {},
                    openableTypes = setOf(SearchHitType.BOOK),
                    highlighted = hit,
                    onDismiss = {},
                )
            }
        },
    )

/**
 * The three browse destinations, in the states that each draw something of their own: a loaded
 * facet, an empty one (the only shape that draws `.brw-none`), a loaded genre with a blurb and
 * sub-genre pills, and each page's Loading and NotFound.
 */
private fun browseShapes(): Map<String, @Composable () -> Unit> {
    fun facet(state: BrowseFacetUiState): @Composable () -> Unit = { BrowseFacetPage(state = state, onOpenBook = {}, onOpenLibrary = {}) }

    fun genre(state: GenreDestinationUiState): @Composable () -> Unit =
        {
            GenreDestinationPage(
                state = state,
                onOpenBook = {},
                onOpenGenre = {},
                onOpenLibrary = {},
                onToggleSubGenres = {},
            )
        }

    return shapes(
        "browse #1: facet(facetReady(kind = FacetKind.Mood," to facet(facetReady(kind = FacetKind.Mood, facetName = "Cosy")),
        "browse #2: facet(facetReady(books = emptyList(), bo" to facet(facetReady(books = emptyList(), bookCount = 0)),
        "browse #3: facet(BrowseFacetUiState.Loading)" to facet(BrowseFacetUiState.Loading),
        "browse #4: facet(BrowseFacetUiState.NotFound(FacetK" to facet(BrowseFacetUiState.NotFound(FacetKind.Tag)),
        "browse #5: genre( genreReady( blurb = Fantasy with" to
            genre(
                genreReady(
                    blurb = "Fantasy with the shine taken off.",
                    breadcrumb = listOf(GenreCrumb(GenreId("g-fantasy"), "Fantasy")),
                    subGenres = listOf(SubGenre(GenreId("g-heroic"), "Heroic", 4)),
                    includeSubGenres = true,
                ),
            ),
        "browse #6: genre(genreReady(books = emptyList(), bo" to genre(genreReady(books = emptyList(), bookCount = 0)),
        "browse #7: genre(GenreDestinationUiState.Loading)" to genre(GenreDestinationUiState.Loading),
        "browse #8: genre(GenreDestinationUiState.NotFound)" to genre(GenreDestinationUiState.NotFound),
    )
}

/** The see-all page in every state, plus the capped group whose "See all" leads to it. */
private fun seeAllShapes(): Map<String, @Composable () -> Unit> {
    fun page(state: SeeAllSearchUiState): @Composable () -> Unit =
        {
            SeeAllPage(
                state = state,
                openableTypes = SearchHitType.entries.toSet(),
                onOpenHit = {},
                onOpenSearch = {},
            )
        }

    val hits = (1..9).map { bookHit("b$it", "Book $it") }
    return shapes(
        shape("seeAll #1: { ResultsList( result = searchResult(que") {
            ResultsList(
                result = searchResult(query = "book", hits = hits),
                openableTypes = SearchHitType.entries.toSet(),
                onOpenHit = {},
                onSeeAll = {},
            )
        },
        "seeAll #2: page(SeeAllSearchUiState.Results(SearchH" to page(SeeAllSearchUiState.Results(SearchHitType.BOOK, "book", hits)),
        "seeAll #3: page(SeeAllSearchUiState.Results(SearchH" to page(SeeAllSearchUiState.Results(SearchHitType.BOOK, "zzz", emptyList())),
        "seeAll #4: page(SeeAllSearchUiState.Loading)" to page(SeeAllSearchUiState.Loading),
        "seeAll #5: page(SeeAllSearchUiState.TooShort)" to page(SeeAllSearchUiState.TooShort),
        "seeAll #6: page(SeeAllSearchUiState.Error(Search un" to page(SeeAllSearchUiState.Error("Search unavailable.")),
    )
}

/**
 * Every shape the two shelf screens can be in.
 *
 * The owner and non-owner variants are both listed: the grip and the remove control exist only for
 * an owner, so a contract that rendered one of them would leave the other's classes unchecked.
 */
private fun shelfShapes(): Map<String, @Composable () -> Unit> {
    val book =
        ShelfBook(
            id = BookId("b1"),
            title = "The Institute",
            authorNames = listOf("Stephen King"),
            coverPath = null,
            coverHash = null,
        )

    fun detail(
        isOwner: Boolean,
        books: List<ShelfBook>,
        isPrivate: Boolean = false,
    ) = ShelfDetailUiState.Ready(
        detail =
            ShelfDetail(
                id = ShelfId("s1"),
                name = "Comfort reads",
                description = "Books to fall asleep to.",
                isPrivate = isPrivate,
                isOwner = isOwner,
                bookCount = books.size,
                totalDurationSeconds = 7_200,
                books = books,
            ),
        isOwner = isOwner,
    )

    fun detailPage(
        state: ShelfDetailUiState,
        notice: String? = null,
    ): @Composable () -> Unit = { ShelfDetailPage(state, notice, {}, {}, {}, {}, {}, {}) }

    fun editPage(
        state: CreateEditShelfUiState,
        isEditing: Boolean,
    ): @Composable () -> Unit = { ShelfEditPage(state, isEditing, { _, _, _ -> }, {}, {}, {}) }

    return shapes(
        "shelf #1: detailPage(ShelfDetailUiState.Loading)" to detailPage(ShelfDetailUiState.Loading),
        // With a notice, so its own classes are checked.
        "shelf #2: detailPage(ShelfDetailUiState.Loading, n" to
            detailPage(ShelfDetailUiState.Loading, notice = "Could not reorder this shelf."),
        "shelf #3: detailPage(ShelfDetailUiState.Error(nope" to detailPage(ShelfDetailUiState.Error("nope")),
        "shelf #4: detailPage(detail(isOwner = true, books" to detailPage(detail(isOwner = true, books = emptyList())),
        "shelf #5: detailPage(detail(isOwner = true, books" to detailPage(detail(isOwner = true, books = listOf(book), isPrivate = true)),
        "shelf #6: detailPage(detail(isOwner = false, books" to detailPage(detail(isOwner = false, books = listOf(book))),
        "shelf #7: editPage(CreateEditShelfUiState.Idle, is" to editPage(CreateEditShelfUiState.Idle, isEditing = false),
        "shelf #8: editPage(CreateEditShelfUiState.LoadingE" to editPage(CreateEditShelfUiState.LoadingExisting, isEditing = true),
        "shelf #9: editPage(CreateEditShelfUiState.Loaded(C" to
            editPage(CreateEditShelfUiState.Loaded("Comfort reads", "", true), isEditing = true),
        "shelf #10: editPage(CreateEditShelfUiState.Saving," to editPage(CreateEditShelfUiState.Saving, isEditing = true),
        "shelf #11: editPage(CreateEditShelfUiState.Error(no" to editPage(CreateEditShelfUiState.Error("nope"), isEditing = true),
    )
}

/**
 * The staged-cover shape, hoisted so its bytes keep ONE identity for the life of this spec.
 *
 * ⛔ Not inline. `CoverPickerField` remembers its object URL keyed on the byte array, and a
 * `ByteArray` compares by identity — so a caller that builds a fresh array in the composable's
 * argument list invalidates that key on every recomposition, and the field creates and revokes a
 * blob URL every frame. Every real caller passes the array the ViewModel is holding, so this is a
 * fixture hazard rather than a product one; inline, it burned enough of the browser's event loop
 * to push `webAuthKotest`'s WebSocket handshakes past their timeout (247 RPC sockets against a
 * normal 126, two transport specs failing) with nothing in the failure pointing back here.
 */
private val stagedCoverSeries = editingSeries(pendingCoverData = byteArrayOf(1, 2, 3))

/** A merge history with a row and an undo outcome, so every class the section can draw is rendered. */
private val contractMergeHistory =
    MergeHistoryState.Ready(
        receipts =
            listOf(
                com.calypsan.listenup.api.dto.MergeReceipt(
                    id =
                        com.calypsan.listenup.core
                            .MergeReceiptId("r1"),
                    sourceName = "Stormlite",
                    mergedAt = 1_700_000_000_000L,
                    mergedByName = "Simon",
                    bookCount = 4,
                ),
            ),
        outcome =
            com.calypsan.listenup.client.presentation.merge.MergeUndoOutcome(
                sourceName = "Mistborne",
                booksRestored = 3,
                booksSkipped = 1,
                restoredAtTopLevel = true,
            ),
    )
