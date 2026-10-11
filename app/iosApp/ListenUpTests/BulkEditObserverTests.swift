import Foundation
import ListenupContract
import Shared
import Testing
@testable import ListenUp

/// Coverage for the bulk metadata editor: every sentence the sheet speaks about counts, which is
/// exactly where a bulk edit would lie about itself if it lied at all, and the `BulkEditUiState` →
/// observer flatten.
@Suite("BulkEditFormatting")
struct BulkEditObserverTests {
    // MARK: - Title

    @Test func titleNamesASingleBookRatherThanCountingIt() {
        #expect(BulkEditFormatting.title(bookCount: 1) == String(localized: "bulk_edit.title_one"))
    }

    @Test func titleCountsASelection() {
        let expected = String(format: String(localized: "bulk_edit.title_plural"), 40)
        #expect(BulkEditFormatting.title(bookCount: 40) == expected)
    }

    // MARK: - Applied confirmation

    @Test func appliedNamesASingleBook() {
        #expect(BulkEditFormatting.applied(changedCount: 1) == String(localized: "bulk_edit.applied_one"))
    }

    /// Reports what changed, not what was selected — the same promise the Apply button made.
    @Test func appliedCountsTheBooksThatChanged() {
        let expected = String(format: String(localized: "bulk_edit.applied_plural"), 8)
        #expect(BulkEditFormatting.applied(changedCount: 8) == expected)
    }

    // MARK: - Relation projections

    private static let catalogue = [
        RelationSearchResult(id: "g1", name: "Science Fiction", subtitle: "Fiction"),
        RelationSearchResult(id: "g2", name: "Historical Fiction", subtitle: "Fiction"),
        RelationSearchResult(id: "g3", name: "Memoir", subtitle: nil)
    ]

    @Test func narrowingMatchesAnywhereInTheNameAndIgnoresCase() {
        let hits = BulkEditMapping.narrow(Self.catalogue, query: "fiction", excluding: [])
        #expect(hits.map(\.id) == ["g1", "g2"])
    }

    /// Something already chosen is not offered again — picking it twice is not a thing to allow.
    @Test func narrowingHidesWhatIsAlreadyChosen() {
        let hits = BulkEditMapping.narrow(Self.catalogue, query: "fiction", excluding: ["g1"])
        #expect(hits.map(\.id) == ["g2"])
    }

    @Test func anEmptyQueryOffersTheWholeCatalogue() {
        #expect(BulkEditMapping.narrow(Self.catalogue, query: "  ", excluding: []).count == 3)
    }

    /// The bug this prevents: one chip for a person credited in two roles, with no way to remove
    /// just one of them.
    @Test func aPersonInTwoRolesIsTwoDistinctChips() {
        let author = BulkEditMapping.contributorChip(name: "Ursula K. Le Guin", roleApiValue: "author")
        let narrator = BulkEditMapping.contributorChip(name: "Ursula K. Le Guin", roleApiValue: "narrator")
        #expect(author.id != narrator.id)
        #expect(author.label != narrator.label)
    }

    @Test func aGenreChipReadsAsItsNameNotItsId() {
        #expect(BulkEditMapping.genreChip(id: "g3", catalogue: Self.catalogue).label == "Memoir")
    }

    /// A genre deleted from another device mid-edit. An id is ugly and findable; a blank chip is
    /// neither.
    @Test func aGenreTheCatalogueCannotNameFallsBackToItsId() {
        #expect(BulkEditMapping.genreChip(id: "gone", catalogue: Self.catalogue).label == "gone")
    }

    /// Tags and moods travel as display names. A slug here is how a library ends up with a tag
    /// literally called `found-family`.
    @Test func aTagChipCarriesTheDisplayName() {
        #expect(BulkEditMapping.nameChip("Found Family").label == "Found Family")
    }

    /// The role picker reads this list, and `roleFromApiValue` must recognise every entry — a value
    /// it cannot map falls back to showing the raw string in the menu.
    @Test func everyAdvertisedRoleResolvesToARealRole() {
        #expect(BookEditObserver.allRoleApiValues.count == 10)
        for apiValue in BookEditObserver.allRoleApiValues {
            #expect(BookEditObserver.roleFromApiValue(apiValue) != nil)
            #expect(BookEditObserver.roleTitle(roleApiValue: apiValue) != apiValue)
        }
    }

    // MARK: - Apply label

    /// The resting state of an untouched form. "Change 0 books" is true and reads as a bug, so the
    /// button names what it does and waits for something to count.
    @Test func applyLabelNamesTheActionWhenNothingWouldChange() {
        #expect(BulkEditFormatting.applyLabel(changedBookCount: 0) == String(localized: "bulk_edit.apply_none"))
    }

    @Test func applyLabelCountsOneBook() {
        #expect(BulkEditFormatting.applyLabel(changedBookCount: 1) == String(localized: "bulk_edit.apply_one"))
    }

    /// The button counts books that will **change**, never books that were selected — promising
    /// forty and then reporting twelve is the overstatement the preview exists to prevent.
    @Test func applyLabelCountsTheBooksThatChangeNotTheSelection() {
        let expected = String(format: String(localized: "bulk_edit.apply_plural"), 12)
        #expect(BulkEditFormatting.applyLabel(changedBookCount: 12) == expected)
    }

    // MARK: - Preview wording

    @Test func affectsSaysNothingChangesWhenNoBookWould() {
        #expect(
            BulkEditFormatting.affects(affectedCount: 0, bookCount: 40)
                == String(localized: "bulk_edit.preview_affects_none")
        )
    }

    @Test func affectsDropsTheCountForASingleSelectedBook() {
        #expect(
            BulkEditFormatting.affects(affectedCount: 1, bookCount: 1)
                == String(localized: "bulk_edit.preview_affects_single_book")
        )
    }

    @Test func affectsCountsAgainstTheSelection() {
        let expected = String(format: String(localized: "bulk_edit.preview_affects_plural"), 12, 40)
        #expect(BulkEditFormatting.affects(affectedCount: 12, bookCount: 40) == expected)
    }

    // MARK: - The books that could not be loaded

    @Test func noNoticeWhenEverySelectedBookLoaded() {
        #expect(BulkEditFormatting.notLoadedNotice(bookCount: 40, requestedCount: 40) == nil)
    }

    /// A book deleted from another device between the grid and the sheet. An operation with no undo
    /// does not get to quietly do less than it was asked to.
    @Test func oneMissingBookIsStatedAgainstWhatWasSelected() {
        let expected = String(format: String(localized: "bulk_edit.some_not_loaded_one"), 40)
        #expect(BulkEditFormatting.notLoadedNotice(bookCount: 39, requestedCount: 40) == expected)
    }

    @Test func severalMissingBooksReportBothCounts() {
        let expected = String(format: String(localized: "bulk_edit.some_not_loaded_plural"), 3, 40)
        #expect(BulkEditFormatting.notLoadedNotice(bookCount: 37, requestedCount: 40) == expected)
    }

    // MARK: - Field placeholders

    @Test func aSharedValueBecomesThePlaceholder() {
        #expect(BulkEditFormatting.placeholder(shared: "Tor") == "Tor")
    }

    @Test func disagreeingBooksSaySoRatherThanShowingOneBooksValue() {
        #expect(BulkEditFormatting.placeholder(shared: nil) == String(localized: "bulk_edit.multiple_values"))
    }

    // MARK: - Failure

    @Test func aFailureBeforeAnythingCommittedIsJustTheReason() {
        #expect(BulkEditFormatting.failureMessage(reason: "No connection.", appliedCount: 0) == "No connection.")
    }

    /// The committed books are not rolled back, so naming them is the difference between "nothing
    /// happened" and "some of it happened" — the only thing the user can act on.
    @Test func aFailurePartWayThroughNamesWhatAlreadyStands() {
        let message = BulkEditFormatting.failureMessage(reason: "No connection.", appliedCount: 1)
        #expect(message.contains("No connection."))
        #expect(message.contains(String(localized: "bulk_edit.failed_after_one")))
    }

    @Test func aFailureAfterSeveralBooksCountsThem() {
        let message = BulkEditFormatting.failureMessage(reason: "No connection.", appliedCount: 5)
        #expect(message.contains(String(format: String(localized: "bulk_edit.failed_after_plural"), 5)))
    }

    // MARK: - Preview rows

    @Test func previewLineIsKeyedByItsFieldAndNamedLikeTheForm() {
        let line = BulkEditMapping.previewLine(field: .publisher, affectedCount: 12, bookCount: 40)

        #expect(line.id == "publisher")
        #expect(line.label == String(localized: "bulk_edit.publisher"))
        #expect(line.detail == String(format: String(localized: "bulk_edit.preview_affects_plural"), 12, 40))
        #expect(line.changesNothing == false)
    }

    /// A row that changes nothing is dimmed, never dropped — a vanished row reads as a lost edit.
    @Test func previewLineMarksAnInstructionThatChangesNothing() {
        let line = BulkEditMapping.previewLine(field: .language, affectedCount: 0, bookCount: 40)

        #expect(line.changesNothing)
        #expect(line.detail == String(localized: "bulk_edit.preview_affects_none"))
    }

    @Test func everyFieldCanNameItself() {
        for field in BulkEditField.allCases {
            #expect(!field.label.isEmpty)
            #expect(field.label != field.rawValue, "\(field.rawValue) fell through to its raw key")
        }
    }

    // MARK: - State mapping

    @MainActor private func makeObserver() -> BulkEditObserver {
        BulkEditObserver(viewModel: Dependencies.shared.createBulkEditViewModel(bookIds: []))
    }

    /// Every field armed, and every count different from every other count.
    private static var armedEditing: BulkEditUiStateEditing {
        let publisherEdit = BulkEditSetPublisher(publisher: "Tor")
        let tagsEdit = BulkEditAddTags(names: ["Found Family"])
        let edits: [any BulkEdit] = [
            publisherEdit,
            BulkEditSetPublishYear(year: 2019),
            BulkEditSetLanguage(language: "en"),
            BulkEditAddToSeries(series: BookSeriesInput(id: nil, name: "Earthsea", position: nil, isPrimary: true)),
            BulkEditAddContributors(contributors: [
                BookContributorInput(id: nil, name: "Ursula K. Le Guin", role: "author", creditedAs: nil, position: 0)
            ]),
            BulkEditAddGenres(genres: [BookGenreInput(genreId: GenreId(value: "g3"))]),
            tagsEdit,
            BulkEditAddMoods(names: ["Cozy"])
        ]
        return BulkEditUiStateEditing(
            bookCount: 40,
            requestedCount: 42,
            edits: edits,
            preview: [
                BulkEditPreviewRow(edit: publisherEdit, affectedCount: 12),
                BulkEditPreviewRow(edit: tagsEdit, affectedCount: 33)
            ],
            changedBookCount: 35,
            isApplying: true,
            sharedPublisher: "Orbit",
            sharedPublishYear: 2001,
            sharedLanguage: nil,
            selectionSample: []
        )
    }

    private static var untouchedEditing: BulkEditUiStateEditing {
        BulkEditUiStateEditing(
            bookCount: 3,
            requestedCount: 5,
            edits: [],
            preview: [],
            changedBookCount: 0,
            isApplying: false,
            sharedPublisher: nil,
            sharedPublishYear: nil,
            sharedLanguage: "fr",
            selectionSample: []
        )
    }

    @MainActor @Test func editingMapsTheCountsAndThePublishingFields() {
        let observer = makeObserver()
        observer.applyState(Self.armedEditing)

        #expect(observer.isLoading == false)
        #expect(observer.bookCount == 40)
        #expect(observer.requestedCount == 42)
        #expect(observer.changedBookCount == 35)
        #expect(observer.canApply)
        #expect(observer.isApplying)
        #expect(observer.publisher == "Tor")
        #expect(observer.year == "2019")
        #expect(observer.language == "en")
        #expect(observer.publisherPlaceholder == "Orbit")
        #expect(observer.yearPlaceholder == "2001")
        #expect(observer.languagePlaceholder == String(localized: "bulk_edit.multiple_values"))
        #expect(observer.selectionCovers.isEmpty)
    }

    @MainActor @Test func editingMapsThePreviewAndTheConsequences() {
        let observer = makeObserver()
        observer.applyState(Self.armedEditing)

        #expect(observer.preview == [
            BulkEditMapping.previewLine(field: .publisher, affectedCount: 12, bookCount: 40, value: "Tor"),
            BulkEditMapping.previewLine(field: .tags, affectedCount: 33, bookCount: 40)
        ])
        #expect(observer.consequences == BulkEditMapping.consequences(
            affectedByField: [.publisher: 12, .tags: 33],
            bookCount: 40,
            sharedPublisher: "Orbit",
            sharedYear: "2001",
            sharedLanguage: nil
        ))
    }

    @MainActor @Test func editingProjectsEveryInstructionOntoItsOwnChips() {
        let observer = makeObserver()
        observer.applyState(Self.armedEditing)

        #expect(observer.seriesChips == [EditableRelation(id: "Earthsea", label: "Earthsea")])
        #expect(observer.contributorChips == [
            BulkEditMapping.contributorChip(name: "Ursula K. Le Guin", roleApiValue: "author")
        ])
        // The genre catalogue arrives on its own flow; with none loaded the chip falls back to the id.
        #expect(observer.genreChips == [EditableRelation(id: "g3", label: "g3")])
        #expect(observer.tagChips == [EditableRelation(id: "Found Family", label: "Found Family")])
        #expect(observer.moodChips == [EditableRelation(id: "Cozy", label: "Cozy")])
    }

    @MainActor @Test func aSecondEditingStateReplacesEveryField() {
        let observer = makeObserver()
        observer.applyState(Self.armedEditing)
        observer.applyState(Self.untouchedEditing)

        #expect(observer.isLoading == false)
        #expect(observer.bookCount == 3)
        #expect(observer.requestedCount == 5)
        #expect(observer.changedBookCount == 0)
        #expect(observer.canApply == false)
        #expect(observer.isApplying == false)
        #expect(observer.publisher.isEmpty)
        #expect(observer.year.isEmpty)
        #expect(observer.language.isEmpty)
        #expect(observer.publisherPlaceholder == String(localized: "bulk_edit.multiple_values"))
        #expect(observer.yearPlaceholder == String(localized: "bulk_edit.multiple_values"))
        #expect(observer.languagePlaceholder == "fr")
        #expect(observer.preview.isEmpty)
        #expect(observer.consequences == BulkEditMapping.consequences(
            affectedByField: [:],
            bookCount: 3,
            sharedPublisher: nil,
            sharedYear: nil,
            sharedLanguage: "fr"
        ))
        #expect(observer.seriesChips.isEmpty)
        #expect(observer.contributorChips.isEmpty)
        #expect(observer.genreChips.isEmpty)
        #expect(observer.tagChips.isEmpty)
        #expect(observer.moodChips.isEmpty)
    }

    @MainActor @Test func loadingPutsTheObserverBackIntoLoading() {
        let observer = makeObserver()
        observer.applyState(Self.untouchedEditing)
        observer.applyState(BulkEditUiStateLoading.shared)
        #expect(observer.isLoading)
    }
}

/// The consequence line is the sentence under every field that says what Apply would do to it. Its
/// three states mirror the Compose editor decision for decision, so they are pinned the same way.
@Suite("BulkEditConsequence")
struct BulkEditConsequenceTests {
    @Test func anUntouchedFieldNamesTheValueTheBooksAgreeOn() {
        let one = BulkEditFormatting.consequence(armedAffectedCount: nil, bookCount: 1, sharedValue: "Macmillan")
        #expect(one.text == String(format: String(localized: "bulk_edit.consequence_agreed_one"), "Macmillan"))
        let many = BulkEditFormatting.consequence(armedAffectedCount: nil, bookCount: 4, sharedValue: "Macmillan")
        #expect(many.text == String(format: String(localized: "bulk_edit.consequence_agreed_plural"), 4, "Macmillan"))
        #expect(!many.writes)
        #expect(many.symbol == "lock.open")
    }

    @Test func anUntouchedFieldTheBooksDisagreeOnSaysSo() {
        let one = BulkEditFormatting.consequence(armedAffectedCount: nil, bookCount: 1, sharedValue: nil)
        #expect(one.text == String(localized: "bulk_edit.consequence_differs_one"))
        let many = BulkEditFormatting.consequence(armedAffectedCount: nil, bookCount: 7, sharedValue: nil)
        #expect(many.text == String(format: String(localized: "bulk_edit.consequence_differs_plural"), 7))
        #expect(!many.writes)
    }

    @Test func anArmedFieldCountsTheBooksItWouldWrite() {
        let some = BulkEditFormatting.consequence(armedAffectedCount: 12, bookCount: 40, sharedValue: nil)
        #expect(some.text == String(format: String(localized: "bulk_edit.consequence_written_plural"), 12, 40))
        #expect(some.writes)
        #expect(some.symbol == "arrow.right")
        let one = BulkEditFormatting.consequence(armedAffectedCount: 1, bookCount: 40, sharedValue: nil)
        #expect(one.text == String(format: String(localized: "bulk_edit.consequence_written_one"), 40))
        let single = BulkEditFormatting.consequence(armedAffectedCount: 1, bookCount: 1, sharedValue: "x")
        #expect(single.text == String(localized: "bulk_edit.consequence_written_single_book"))
    }

    @Test func aTypedValueEveryBookAlreadyHoldsWritesNothingAndSaysSo() {
        let none = BulkEditFormatting.consequence(armedAffectedCount: 0, bookCount: 40, sharedValue: "Macmillan")
        #expect(none.text == String(localized: "bulk_edit.consequence_written_none"))
        #expect(!none.writes)
        #expect(none.symbol == "minus")
    }

    @Test func theLeftAloneNoteNamesTheRestOnlyWhenThereIsARestAndAValue() {
        #expect(BulkEditFormatting.leftAloneNote(value: "2019", affectedCount: 40, bookCount: 40) == nil)
        #expect(BulkEditFormatting.leftAloneNote(value: nil, affectedCount: 3, bookCount: 40) == nil)
        #expect(
            BulkEditFormatting.leftAloneNote(value: "2019", affectedCount: 39, bookCount: 40)
                == String(format: String(localized: "bulk_edit.preview_note_one"), "2019")
        )
        #expect(
            BulkEditFormatting.leftAloneNote(value: "2019", affectedCount: 12, bookCount: 40)
                == String(format: String(localized: "bulk_edit.preview_note_plural"), 28, "2019")
        )
    }

    @Test func thePreviewLineCarriesItsProportionAndNote() {
        let line = BulkEditMapping.previewLine(field: .year, affectedCount: 10, bookCount: 40, value: "2019")
        #expect(line.fraction == 0.25)
        #expect(line.note != nil)
        #expect(line.symbol == BulkEditField.year.symbol)
        let whole = BulkEditMapping.previewLine(field: .genres, affectedCount: 40, bookCount: 40)
        #expect(whole.fraction == 1)
        #expect(whole.note == nil)
    }

    @Test func theHeroNamesTheOverflowOnlyWhenThereIsOne() {
        #expect(BulkEditFormatting.heroMore(bookCount: 4, shown: 4) == nil)
        #expect(
            BulkEditFormatting.heroMore(bookCount: 40, shown: 4)
                == String(format: String(localized: "bulk_edit.hero_more"), 36)
        )
        #expect(
            BulkEditFormatting.heroEyebrow(selectedCount: 40)
                == String(format: String(localized: "bulk_edit.hero_eyebrow"), 40)
        )
    }
}
