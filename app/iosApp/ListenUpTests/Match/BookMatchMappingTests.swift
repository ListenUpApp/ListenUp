import Foundation
import Testing
import Shared
@testable import ListenUp

// Match details' boundary: every shared Find, Review and receipt state lands on its native value, so the
// views only lay out what the mapping already said.

@MainActor
@Suite("Match details mapping")
struct BookMatchMappingTests {
    private typealias Fixture = MatchFixtures

    // MARK: - Find

    @Test func aCandidateRowSaysEverythingThatTellsEditionsApart() {
        let row = BookMatchMapping.candidate(Fixture.candidate(isCurrentLink: true))
        #expect(row.isStrong && row.isBest && row.isCurrentLink)
        #expect(row.metadataLine == "Ray Porter · 16h 10m · 2021 · Unabridged")
        #expect(row.reasons.map(\.text) == ["Same narrator", "Same length", "36 chapters"])
        #expect(row.foundInLine == "Storefront · Shelfdata · Tuneshop")
        #expect(row.foundInList == "Storefront, Shelfdata and Tuneshop")
        #expect(row.compare.length == "16h 10m")
        #expect(row.compare.chapters == "36")
    }

    @Test func compareSaysNotListedForWhatAnEditionDoesntSay() {
        let bare = BookMatchMapping.candidate(
            Fixture.candidate(narrators: [], durationMs: nil, year: nil, format: nil, chapterCount: nil, foundIn: [])
        )
        let rows = MatchCompareSheet.rows(yourCopy: nil, candidate: bare.compare)
        #expect(rows.map(\.name) == ["Length", "Narrator", "Chapters", "Year", "Format", "Store", "Found in"])
        #expect(rows.allSatisfy { $0.theirs == "Not listed" && $0.yours == "Not listed" })
    }

    @Test func searchingKeepsThePreviousResultsOnScreen() {
        let previous = FindUiStateResults(
            yourCopy: nil, steps: [], query: "hail mary", strong: [Fixture.candidate()], maybe: [], partialFailure: nil,
            region: nil, pickedKey: nil
        )
        let find = BookMatchMapping.find(from: FindUiStateSearching(yourCopy: nil, query: "hail", previous: previous))
        guard case .searching(let shown) = find.phase else {
            Issue.record("expected .searching")
            return
        }
        #expect(shown?.strong.map(\.id) == ["storefront:B1:us"])
        #expect(find.query == "hail")
    }

    @Test func theRowLastOpenedInReviewIsMarked() {
        let results = FindUiStateResults(
            yourCopy: nil, steps: [], query: "q", strong: [Fixture.candidate()],
            maybe: [Fixture.candidate(id: "storefront:B2:us", isStrong: false, isBest: false)], partialFailure: nil,
            region: nil, pickedKey: Fixture.key("storefront:B2:us")
        )
        #expect(BookMatchMapping.results(results).pickedId == "storefront:B2:us")
        #expect(BookMatchMapping.results(results).maybe.first?.isStrong == false)
    }

    @Test func yourCopyComposesItsLineAndHowFindStarted() {
        let copy = YourCopyUi(
            title: "Project Hail Mary", authors: ["Andy Weir"], coverPath: nil, coverHash: nil, durationMs: 58_200_000,
            narrators: ["Ray Porter"], chapterCount: 36, year: 2021, isAbridged: false
        )
        let mapped = BookMatchMapping.yourCopy(copy, steps: [SearchStepExistingLink(source: Fixture.storefront)])
        #expect(mapped.detailLine == "16h 10m · Ray Porter · 36 chapters")
        #expect(mapped.stepsLine == "Started from your Storefront link.")
        #expect(mapped.compare.foundIn == "In your library")
    }

    // MARK: - Failures

    @Test func offlineOffersTryAgain() {
        let failure = BookMatchMapping.failure(FindFailureOffline.shared)
        #expect(failure.title == "You're offline")
        #expect(failure.message == "Matching needs the server, and the server needs its sources.")
        #expect(failure.actions == [.retry(title: "Try Again")])
    }

    @Test func aTimeoutNamesTheSourceAndSaysNothingChanged() {
        let failure = BookMatchMapping.failure(FindFailureTimedOut(source: Fixture.storefront))
        #expect(failure.title == "Storefront didn't answer in time")
        #expect(failure.message == "Nothing was changed. This usually clears in a moment.")
    }

    @Test func aRateLimitDisablesRetryUntilTheCountdownEnds() {
        let waiting = BookMatchMapping.failure(FindFailureRateLimited(source: Fixture.shelfdata, secondsRemaining: 30))
        #expect(waiting.actions == [.retryCountdown(title: "Retry in 0:30", isEnabled: false)])
        #expect(waiting.actions.first?.isEnabled == false)
        let ready = BookMatchMapping.failure(FindFailureRateLimited(source: Fixture.shelfdata, secondsRemaining: 0))
        #expect(ready.actions.first?.isEnabled == true)
    }

    @Test func notFoundOffersAtMostTwoStoresThenSearchByTitle() {
        let failure = BookMatchMapping.failure(FindFailureNotFoundInStore(
            source: Fixture.storefront, region: MetadataLocale(region: "uk", language: nil),
            suggestions: [Fixture.unitedStates, MetadataLocale(region: "au", language: nil), MetadataLocale(region: "ca", language: nil)]
        ))
        #expect(failure.actions.count == 3)
        #expect(failure.actions.prefix(2).allSatisfy { if case .tryStore = $0 { true } else { false } })
        #expect(failure.actions.last == .searchByTitle(title: "Search by title"))
        #expect(failure.title.hasPrefix("No match in the "))
    }

    @Test func nothingFoundOffersSearchByTitle() {
        let failure = BookMatchMapping.failure(FindFailureNothingFound.shared)
        #expect(failure.actions == [.searchByTitle(title: "Search by title")])
    }

    // MARK: - Review fields

    @Test func aChangedFieldIsTickedAndSaysWhereItsFrom() {
        let row = BookMatchMapping.field(Fixture.field(), viewerId: nil)
        #expect(row.isTicked)
        #expect(!row.isEdited)
        #expect(row.tickLabel == "Description, proposed from Storefront, changes yours")
        #expect(row.proposedFrom == "from Storefront")
        #expect(row.yours == "A lone astronaut wakes far from home.")
        #expect(row.proposed == "Ryland Grace is the sole survivor.")
    }

    @Test func aFieldThatFillsAGapSaysSoAndHasNothingToKeep() {
        let row = BookMatchMapping.field(Fixture.field(.publisher, state: .fillsGap, current: nil), viewerId: nil)
        #expect(row.tickLabel == "Publisher, proposed from Storefront, fills a gap")
        #expect(!row.segments.contains { $0.selection == .keepYours })
        #expect(row.valuesLabel == "Publisher. Yours: —. Proposed from Storefront: Ryland Grace is the sole survivor.")
    }

    @Test func theSourceSwitchOffersEverySourceAndKeepYours() {
        let row = BookMatchMapping.field(Fixture.field(), viewerId: nil)
        #expect(row.segments.map(\.title) == ["Storefront", "Shelfdata", "Keep yours"])
        #expect(row.switchStyle == .segmented)
        #expect(row.selectedSegment == .option("o1"))
    }

    @Test func moreThanFourSegmentsBecomeAMenu() {
        let options = (1...4).map {
            FieldOptionUi(optionId: "o\($0)", value: Fixture.text("Value \($0)"), sources: [MetadataSource(id: "s\($0)", label: "S\($0)")])
        }
        #expect(BookMatchMapping.field(Fixture.field(options: options), viewerId: nil).switchStyle == .menu)
        #expect(BookMatchMapping.field(Fixture.field(options: Array(options.prefix(3))), viewerId: nil).switchStyle == .segmented)
        let single = Array(options.prefix(1))
        #expect(BookMatchMapping.field(Fixture.field(state: .fillsGap, current: nil, options: single), viewerId: nil).switchStyle == .none)
    }

    @Test func anUntickedFieldShowsKeepYoursInItsSwitch() {
        let row = BookMatchMapping.field(Fixture.field(ticked: false), viewerId: nil)
        #expect(!row.isTicked)
        #expect(row.selectedSegment == .keepYours)
    }

    @Test func aHandEditedFieldIsFlaggedAndSaysWhoseEditItWas() {
        let edit = HandEdit(byUserId: "u1", byName: "Simon", at: nil)
        let row = BookMatchMapping.field(Fixture.field(.title, state: .userEdited, ticked: false, handEdit: edit), viewerId: "u1")
        #expect(row.isEdited)
        #expect(!row.isTicked)
        #expect(row.editedNote == "Edited by you. Kept unless you tick it.")
    }

    @Test func choicesRoundTripToTheSharedTypes() {
        #expect(BookMatchObserver.fieldChoice(.keepYours) is FieldChoiceKeepCurrent)
        #expect((BookMatchObserver.fieldChoice(.option("o2")) as? FieldChoiceOption)?.optionId == "o2")
        #expect(BookMatchObserver.imageChoice("keep") is ImageChoiceKeepCurrent)
        #expect((BookMatchObserver.imageChoice("c2") as? ImageChoiceCandidate)?.optionId == "c2")
    }

    // MARK: - Cover

    @Test func theCoverIsARadioGroupStartingWithKeepCurrent() throws {
        let section = try #require(BookMatchMapping.cover(Fixture.cover()))
        #expect(section.tiles.map(\.id) == ["keep", "c1", "c2"])
        #expect(section.tiles.filter(\.isSelected).map(\.id) == ["c2"])
        #expect(section.chosenSource == "Shelfdata")
        #expect(section.tiles[2].accessibilityLabel == "Cover from Shelfdata, 1600 by 2400")
        #expect(section.bookCoverPath == "/covers/b1.jpg")
    }

    @Test func keepCurrentIsSelectedWhenNoCandidateIs() throws {
        let section = try #require(BookMatchMapping.cover(Fixture.cover(choice: ImageChoiceKeepCurrent.shared)))
        #expect(section.tiles.filter(\.isSelected).map(\.id) == ["keep"])
        #expect(section.chosenSource == nil)
    }

    // MARK: - Sections

    @Test func emptySectionsAreNotRendered() {
        let review = BookMatchMapping.review(Fixture.ready(), viewerId: nil)
        #expect(review.fillsGap.isEmpty && review.youEdited.isEmpty)
        #expect(review.labels.isEmpty)
        #expect(review.chapters == nil)
        #expect(review.alreadySame == nil)
    }

    @Test func alreadyTheSameIncludesLength() {
        let review = BookMatchMapping.review(Fixture.ready(alreadySame: [.authors], lengthAlreadySame: true), viewerId: nil)
        #expect(review.alreadySame == "2 fields already match: Authors, Length")
    }

    @Test func genresKeepYoursAndSuggestWithSources() throws {
        let genres = LabelSetUi(
            yours: [YourLabelUi(label: "Science Fiction", removed: false)],
            suggested: [SuggestionUi(label: "Thriller", sources: [Fixture.shelfdata], selected: true)]
        )
        let review = BookMatchMapping.review(Fixture.ready(genres: genres), viewerId: nil)
        let group = try #require(review.labels.first)
        #expect(review.labels.count == 1)
        #expect(group.yours.map(\.label) == ["Science Fiction"])
        #expect(group.suggested.first?.accessibilityLabel == "Thriller, from Shelfdata")
        #expect(MatchReviewContent.addedCount(review.labels) == "+1")
    }

    @Test func chapterNamesSayHowManyChangeAndHowManyAlreadyMatch() {
        let available = ChapterNamesUiAvailable(
            source: Fixture.storefront,
            rows: (1...16).map { ChapterRowUi(ordinal: Int32($0), yours: "Track \($0)", theirs: "Chapter \($0)", selected: true) },
            unchangedCount: 20, included: true
        )
        guard case .available(let summary, let included, let rows) = BookMatchMapping.chapters(available) else {
            Issue.record("expected .available")
            return
        }
        #expect(summary == "16 of 36 chapters get names from Storefront. The other 20 already match.")
        #expect(included)
        #expect(rows.first?.accessibilityLabel == "Chapter 1: Track 1 becomes Chapter 1")
    }

    @Test func aDifferentChapterCountIsShownNotApplied() {
        let mismatch = ChapterNamesUiCountMismatch(source: Fixture.storefront, yours: 36, theirs: 40)
        #expect(BookMatchMapping.chapters(mismatch)
            == .mismatch(message: "Storefront has 40 chapters and yours has 36, so its names can't be matched."))
    }

    @Test func theApplyBarCarriesTheErrorAndWhetherApplyCanRun() {
        let error = UnknownError(code: "X", message: "The server refused that.", correlationId: nil, debugInfo: nil, isRetryable: false)
        let review = BookMatchMapping.review(Fixture.ready(applying: false, applyError: error), viewerId: nil)
        #expect(review.applyBar.error == "The server refused that. Nothing was changed.")
        #expect(review.applyBar.summary == "1 field · cover")
        #expect(review.applyBar.canApply)
        let nothing = BookMatchMapping.review(
            Fixture.ready(applyBar: ApplySummary(fieldCount: 0, coverChanges: false, chapterNameCount: 0)), viewerId: nil
        )
        #expect(!nothing.applyBar.canApply)
    }

    // MARK: - Receipt

    @Test func theReceiptPhaseCarriesItsSentenceAndChanges() {
        let receipt = MatchReceiptUi(
            receiptId: "r1", fieldCount: 1, coverSource: Fixture.shelfdata, chapterNameCount: 0,
            photoSource: nil, biographySource: nil,
            changes: [AppliedChangeField(field: .publisher, source: Fixture.storefront), AppliedChangeCover(source: Fixture.shelfdata)],
            undoable: true
        )
        let phase = BookMatchMapping.receipt(from: MatchReceiptUiStateShown(receipt: receipt, undoing: false, undoError: nil))
        guard case .shown(let model) = phase else {
            Issue.record("expected .shown")
            return
        }
        #expect(model.sentence == "Changed 1 field, cover from Shelfdata")
        #expect(model.changes == ["Publisher · from Storefront", "Cover · from Shelfdata"])
        #expect(model.canUndo && !model.undoing)
        #expect(BookMatchMapping.receipt(from: MatchReceiptUiStateUndone.shared) == .undone)
        #expect(BookMatchMapping.receipt(from: MatchReceiptUiStateExpired.shared)
            == .expired(message: "This book has changed since, so the match can't be undone."))
        #expect(BookMatchMapping.receipt(from: MatchReceiptUiStateNone.shared) == .none)
    }
}
