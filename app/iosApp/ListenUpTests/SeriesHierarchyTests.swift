import Foundation
import Testing
import Shared
@testable import ListenUp

/// The series hierarchy's iOS seams: every sentence it speaks, the projections each observer makes
/// from the shared Kotlin state (hand-built here — no observer, no flow), the Book Detail path fold,
/// and the one-order-per-drop reorder.
@Suite("SeriesHierarchyText")
struct SeriesHierarchyTextTests {
    @Test func countsBooksInTheSingularAndPlural() {
        #expect(SeriesHierarchyText.books(1) == "1 book")
        #expect(SeriesHierarchyText.books(8) == "8 books")
    }

    @Test func aParentCountsItsSeriesAndBooks() {
        #expect(SeriesHierarchyText.seriesAndBooks(seriesCount: 4, bookCount: 23) == "4 series · 23 books")
        #expect(SeriesHierarchyText.subSeries(2) == "2 series")
    }

    @Test func placementNamesThePathThenTheCount() {
        #expect(
            SeriesHierarchyText.placement(path: ["Cosmere", "Mistborn"], bookCount: 4)
                == "in Cosmere › Mistborn · 4 books"
        )
    }

    @Test func aTopLevelSeriesShowsJustItsCount() {
        #expect(SeriesHierarchyText.placement(path: [], bookCount: 8) == "8 books")
    }

    @Test func placementLeavesOutAnUnknownOrEmptyCount() {
        #expect(SeriesHierarchyText.placement(path: ["Cosmere"], bookCount: nil) == "in Cosmere")
        #expect(SeriesHierarchyText.placement(path: ["Cosmere"], bookCount: 0) == "in Cosmere")
        #expect(SeriesHierarchyText.placement(path: [], bookCount: 0) == nil)
    }

    @Test func childMetaReadsProgressAsWords() {
        #expect(SeriesHierarchyText.childMeta(bookCount: 8, finishedCount: 2) == "8 books · 2 finished")
        #expect(SeriesHierarchyText.childMeta(bookCount: 1, finishedCount: 1) == "1 book · Finished")
        #expect(SeriesHierarchyText.childMeta(bookCount: 3, finishedCount: 0) == "3 books · Not started")
    }

    /// One VoiceOver element per card, its progress as counts — never a percentage.
    @Test func childCardSpeaksAsOneSentence() {
        #expect(
            SeriesHierarchyText.childAccessibilityLabel(
                name: "Mistborn", subSeriesCount: 2, bookCount: 8, finishedCount: 2
            ) == "Mistborn, 2 series, 8 books, 2 finished"
        )
        #expect(
            SeriesHierarchyText.childAccessibilityLabel(
                name: "Elantris", subSeriesCount: 0, bookCount: 1, finishedCount: 1
            ) == "Elantris, 1 book, Finished"
        )
    }

    @Test func groupedContinueNamesTheBookAndWhereItSits() {
        #expect(SeriesHierarchyText.continueTitle(bookTitle: "The Hero of Ages") == "Continue The Hero of Ages")
        #expect(
            SeriesHierarchyText.continueWhere(seriesName: "Mistborn Era 1", sequence: "3") == "Mistborn Era 1 · Book 3"
        )
        #expect(SeriesHierarchyText.continueWhere(seriesName: "Secret Projects", sequence: nil) == "Secret Projects")
    }

    @Test func groupHeadingsAndExpansion() {
        #expect(SeriesHierarchyText.alsoIn("Cosmere") == "Also in Cosmere")
        #expect(SeriesHierarchyText.showAll(4) == "Show all 4")
    }

    @Test func aMoveIsAnnouncedWithItsNewPosition() {
        #expect(
            SeriesHierarchyText.moved(name: "Mistborn Era 1", position: 2, total: 2)
                == "Mistborn Era 1 moved to position 2 of 2"
        )
    }

    @Test func dialogsSayWhatTheyWillDo() {
        #expect(
            SeriesHierarchyText.moveConfirmTitle(series: "City Watch", from: "Discworld", into: "Cosmere")
                == "Move City Watch out of Discworld into Cosmere?"
        )
        #expect(SeriesHierarchyText.addSubSeriesTitle(parent: "Cosmere") == "Add sub-series to Cosmere")
        #expect(SeriesHierarchyText.moveIntoTitle(series: "Mistborn") == "Move “Mistborn” into…")
        #expect(
            SeriesHierarchyText.newSubSeriesBody(name: " White Sand ", parent: "Cosmere")
                == "Creates “White Sand” inside Cosmere."
        )
        #expect(
            SeriesHierarchyText.newParentBody(name: "Cosmere", series: "Mistborn")
                == "Creates “Cosmere” and moves Mistborn into it."
        )
        #expect(SeriesHierarchyText.nameExists("Cosmere") == "“Cosmere” already exists.")
    }

    /// Nothing typed yet: no half-sentence about creating “”.
    @Test func anEmptyNameExplainsNothing() {
        #expect(SeriesHierarchyText.newSubSeriesBody(name: "  ", parent: "Cosmere") == nil)
        #expect(SeriesHierarchyText.newParentBody(name: "", series: "Mistborn") == nil)
    }
}

@Suite("SeriesDetail grouped Continue")
struct SeriesGroupedContinueTests {
    @Test func startedNamesTheBook() {
        #expect(
            SeriesDetailObserver.groupedContinueLabel(resumeTitle: "The Hero of Ages", hasStarted: true)
                == "Continue The Hero of Ages"
        )
    }

    /// "Start Book 1" would be ambiguous across four series; the line underneath says where.
    @Test func neverStartedStartsListening() {
        #expect(
            SeriesDetailObserver.groupedContinueLabel(resumeTitle: "The Final Empire", hasStarted: false)
                == "Start listening"
        )
    }

    @Test func allFinishedListensAgain() {
        #expect(SeriesDetailObserver.groupedContinueLabel(resumeTitle: nil, hasStarted: true) == "Listen again")
    }
}

@Suite("SeriesPathModel")
struct SeriesPathModelTests {
    private let cosmere = SeriesCrumbItem(id: "c", name: "Cosmere")
    private let mistborn = SeriesCrumbItem(id: "m", name: "Mistborn")
    private let era = SeriesCrumbItem(id: "e", name: "Era 1")

    @Test func theBreadcrumbIsEveryAncestorRootFirst() {
        #expect(
            SeriesPathModel.breadcrumb([cosmere, mistborn])
                == [.link(id: "c", label: "Cosmere"), .link(id: "m", label: "Mistborn")]
        )
    }

    @Test func aBookLineEndsWithTheSeriesAndTheBooksNumber() {
        let parts = SeriesPathModel.bookLine(
            ancestors: [cosmere, mistborn], seriesId: "e1", seriesName: "Mistborn Era 1", sequence: "1", expanded: false
        )
        #expect(parts == [
            .link(id: "c", label: "Cosmere"),
            .link(id: "m", label: "Mistborn"),
            .link(id: "e1", label: "Mistborn Era 1 #1")
        ])
    }

    @Test func anUnnumberedTopLevelSeriesIsJustItsName() {
        let parts = SeriesPathModel.bookLine(
            ancestors: [], seriesId: "w", seriesName: "Warbreaker", sequence: nil, expanded: false
        )
        #expect(parts == [.link(id: "w", label: "Warbreaker")])
    }

    /// Four levels or more fold the middle into "…", until the reader expands it.
    @Test func aDeepPathFoldsItsMiddle() {
        let folded = SeriesPathModel.bookLine(
            ancestors: [cosmere, mistborn, era], seriesId: "p", seriesName: "Part 1", sequence: "2", expanded: false
        )
        #expect(folded == [.link(id: "c", label: "Cosmere"), .fold, .link(id: "p", label: "Part 1 #2")])

        let expanded = SeriesPathModel.bookLine(
            ancestors: [cosmere, mistborn, era], seriesId: "p", seriesName: "Part 1", sequence: "2", expanded: true
        )
        #expect(expanded.count == 4)
        #expect(!expanded.contains(.fold))
    }

    @Test func aBookSeriesPathProjectsFromTheSharedType() {
        let path = BookSeriesPath(
            seriesId: "e1",
            seriesName: "Mistborn Era 1",
            sequence: "1",
            ancestors: [SeriesCrumb(id: "c", name: "Cosmere"), SeriesCrumb(id: "m", name: "Mistborn")]
        )
        let item = BookSeriesPathItem(path)
        #expect(item.ancestors == [cosmere, mistborn])
        #expect(item.parts(expanded: false).last == .link(id: "e1", label: "Mistborn Era 1 #1"))
    }
}

@Suite("Series page projections")
struct SeriesPageProjectionTests {
    @Test func aChildCardCarriesItsCountsAndStackHint() {
        let card = ChildSeriesCard(
            ChildSeriesUi(id: "m", name: "Mistborn", coverPath: nil, bookCount: 8, finishedCount: 2, subSeriesCount: 2)
        )
        #expect(card.meta == "8 books · 2 finished")
        #expect(card.subSeriesHint == "2 series")
        #expect(card.progress == 0.25)
        #expect(card.accessibilityLabel == "Mistborn, 2 series, 8 books, 2 finished")
    }

    @Test func aLeafCardHasNoStackHint() {
        let card = ChildSeriesCard(
            id: "e", name: "Elantris", coverPath: nil, bookCount: 1, finishedCount: 0, subSeriesCount: 0
        )
        #expect(card.subSeriesHint == nil)
        #expect(card.progress == 0)
    }

    @Test func aSectionProjectsFromTheSharedType() {
        let group = SeriesBookGroup(
            SeriesBookSection(
                key: "sub:e1",
                seriesId: "e1",
                title: "Mistborn Era 1",
                kind: .subSeries,
                path: ["Mistborn", "Mistborn Era 1"],
                depth: 2,
                books: [],
                bookCount: 4,
                finishedCount: 4,
                isCollapsed: true
            )
        )
        #expect(group.id == "sub:e1")
        #expect(group.kind == .subSeries)
        #expect(group.isCollapsed)
        #expect(group.isCollapsible)
        // A nested heading draws its path.
        #expect(group.heading == "Mistborn › Mistborn Era 1")
        #expect(group.countLabel == "4 books")
    }

    @Test func aTopLevelSubSeriesHeadingIsItsName() {
        #expect(group(kind: .subSeries, depth: 1).heading == "Mistborn")
    }

    @Test func ownBooksReadAlsoIn() {
        let own = group(kind: .ownBooks, depth: 1)
        #expect(own.heading == "Also in Mistborn")
        #expect(!own.isCollapsible)
    }

    private func group(kind: SeriesGroupKind, depth: Int) -> SeriesBookGroup {
        SeriesBookGroup(
            id: "k", seriesId: "m", title: "Mistborn", kind: kind, path: ["Mistborn"], depth: depth,
            books: [], bookCount: 8, finishedCount: 0, isCollapsed: false
        )
    }
}

@Suite("Add sub-series sheet")
struct AddSubSeriesSheetTests {
    private func candidate(_ placement: SubSeriesPlacement, parent: String?) -> SubSeriesCandidateUi {
        SubSeriesCandidateUi(
            id: "x", name: "City Watch", coverPath: nil, bookCount: 3, subSeriesCount: 0,
            placement: placement, currentParentName: parent
        )
    }

    @Test func aTopLevelSeriesJustMovesIn() {
        let item = SubSeriesCandidateItem(candidate(.topLevel, parent: nil), pageName: "Cosmere")
        #expect(item.meta == "Top level · 3 books")
        #expect(item.isSelectable)
    }

    @Test func aSeriesElsewhereSaysItWillMove() {
        let item = SubSeriesCandidateItem(candidate(.inOtherParent, parent: "Discworld"), pageName: "Cosmere")
        #expect(item.meta == "In Discworld · moves it here")
        #expect(item.isSelectable)
    }

    @Test func aSeriesAlreadyHereIsListedButNotChoosable() {
        let item = SubSeriesCandidateItem(candidate(.alreadyHere, parent: "Cosmere"), pageName: "Cosmere")
        #expect(item.meta == "Already in Cosmere")
        #expect(!item.isSelectable)
    }

    @Test func aClosedSheetIsInvisible() {
        let model = AddSubSeriesSheetModel(AddSubSeriesUiStateClosed(isBusy: true, error: nil))
        #expect(!model.isVisible)
        #expect(model.isBusy)
    }

    @Test func anOpenSheetMapsItsMoveAndDraft() {
        let state = AddSubSeriesUiStateOpen(
            parentName: "Cosmere",
            query: "ci",
            candidates: [candidate(.inOtherParent, parent: "Discworld")],
            pendingMove: PendingSubSeriesMove(
                seriesId: "x", seriesName: "City Watch", fromParentName: "Discworld", toParentName: "Cosmere"
            ),
            newSeries: NewSeriesDraft(
                name: "Mistborn",
                existing: ExistingSeriesMatch(id: "m", name: "Mistborn", isSelectable: true)
            ),
            isBusy: false,
            error: nil
        )
        let model = AddSubSeriesSheetModel(state)
        #expect(model.isVisible)
        #expect(model.title == "Add sub-series to Cosmere")
        #expect(model.query == "ci")
        #expect(model.candidates.map(\.id) == ["x"])
        #expect(model.pendingMove?.title == "Move City Watch out of Discworld into Cosmere?")
        #expect(model.newSeries?.existing == ExistingSeriesItem(id: "m", name: "Mistborn", isSelectable: true))
        #expect(model.newSeries?.canCreate == false)
    }
}

@Suite("Move into… picker")
struct ParentPickerTests {
    private func row(
        reason: ParentPickerDisabledReason?,
        path: [String] = [],
        subSeries: Int32 = 0
    ) -> ParentPickerItem {
        ParentPickerItem(
            ParentPickerRow(
                id: "e1", name: "Mistborn Era 1", depth: 1, pathNames: path, bookCount: 4,
                subSeriesCount: subSeries, isExpanded: false, disabledReason: reason
            )
        )
    }

    @Test func aDisabledRowKeepsItsReason() {
        let inside = row(reason: .insideThisSeries)
        #expect(!inside.isSelectable)
        #expect(inside.reason?.text(seriesName: "Mistborn") == "Inside Mistborn")
        #expect(row(reason: .currentParent).reason?.text(seriesName: "Mistborn") == "Current")
        #expect(row(reason: .thisSeries).reason?.text(seriesName: "Mistborn") == "This series")
    }

    @Test func aChoosableRowHasNoReason() {
        #expect(row(reason: nil).isSelectable)
    }

    @Test func treeRowsCountWhatTheyHold() {
        #expect(row(reason: nil).meta == "4 books")
        let parent = row(reason: nil, subSeries: 2)
        #expect(parent.hasChildren)
        #expect(parent.meta == "2 series · 4 books")
    }

    /// While searching, rows are flat, so each says where it sits.
    @Test func searchRowsNameTheirPlace() {
        #expect(row(reason: nil, path: ["Cosmere", "Mistborn"]).meta == "in Cosmere › Mistborn · 4 books")
    }
}

@Suite("SubSeriesOrder")
struct SubSeriesOrderTests {
    @Test func aDragProducesTheWholeNewOrder() {
        let order = SubSeriesOrder.moving(["a", "b", "c"], fromOffsets: IndexSet(integer: 0), toOffset: 3)
        #expect(order == ["b", "c", "a"])
    }

    @Test func moveEarlierAndLaterStepOnePlace() {
        #expect(SubSeriesOrder.stepping(["a", "b", "c"], id: "b", by: -1) == ["b", "a", "c"])
        #expect(SubSeriesOrder.stepping(["a", "b", "c"], id: "b", by: 1) == ["a", "c", "b"])
    }

    @Test func steppingPastAnEndDoesNothing() {
        #expect(SubSeriesOrder.stepping(["a", "b"], id: "a", by: -1) == nil)
        #expect(SubSeriesOrder.stepping(["a", "b"], id: "b", by: 1) == nil)
        #expect(SubSeriesOrder.stepping(["a", "b"], id: "z", by: 1) == nil)
    }
}

@Suite("Where a series sits, elsewhere")
struct SeriesPlacementElsewhereTests {
    @Test func aParentLibraryCardCountsSeriesAndBooks() {
        let parent = SeriesRow(
            id: "c", name: "Cosmere", bookCount: 23, authorName: "Sanderson", covers: [], subSeriesCount: 4
        )
        #expect(parent.isParent)
        #expect(parent.meta == "4 series · 23 books")
    }

    @Test func aFlatLibraryCardKeepsItsAuthorLine() {
        let flat = SeriesRow(id: "d", name: "Dune", bookCount: 6, authorName: "Herbert", covers: [])
        #expect(!flat.isParent)
        #expect(flat.meta == "6 books · Herbert")
    }

    /// Book edit's and bulk edit's series pickers both read this.
    @Test func aPickedSeriesSaysWhereItSits() {
        let nested = BookEditObserver.seriesResult(
            SeriesSearchResult(id: "e1", name: "Mistborn Era 1", bookCount: 4, parentPath: ["Cosmere", "Mistborn"])
        )
        #expect(nested.subtitle == "in Cosmere › Mistborn · 4 books")
        let root = BookEditObserver.seriesResult(
            SeriesSearchResult(id: "c", name: "Cosmere", bookCount: 23, parentPath: [])
        )
        #expect(root.subtitle == "23 books")
    }
}
