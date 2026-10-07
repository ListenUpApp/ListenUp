import Foundation
import Testing
import Shared
@testable import ListenUp

// Every sentence Match details composes, pinned to the canvas wording (boards I-01 to I-05).

@Suite("Match details copy")
struct MatchCopyTests {
    private typealias F = MatchFixtures

    // MARK: - Lists

    @Test func listsJoinTheWayASentenceDoes() {
        #expect(MatchCopy.list([]) == "")
        #expect(MatchCopy.list(["A"]) == "A")
        #expect(MatchCopy.list(["A", "B"]) == "A and B")
        #expect(MatchCopy.list(["A", "B", "C"]) == "A, B and C")
    }

    @Test func eachSourceIsNamedOnceInTheOrderGiven() {
        #expect(MatchCopy.sourceLabels([F.shelfdata, F.storefront, F.shelfdata]) == ["Shelfdata", "Storefront"])
    }

    // MARK: - Find

    @Test func theStepsLineSaysWhereFindStarted() {
        let steps: [any SearchStep] = [SearchStepExistingLink(source: F.storefront), SearchStepTitleAuthorLength.shared]
        #expect(MatchCopy.stepsLine(steps) == "Started from your Storefront link, then title, author and length.")
        #expect(MatchCopy.stepsLine([SearchStepYourQuery(query: "hail mary")]) == "Started from your search “hail mary”.")
        #expect(MatchCopy.stepsLine([]) == nil)
    }

    @Test func reasonsReadAsTheCanvasWritesThem() {
        #expect(MatchCopy.reason(MatchReasonSameNarrator.shared) == "Same narrator")
        #expect(MatchCopy.reason(MatchReasonLengthWithin(minutes: 1)) == "Length within 1 min")
        #expect(MatchCopy.reason(MatchReasonLengthDiffers(deltaMinutes: -386)) == "6h 26m shorter")
        #expect(MatchCopy.reason(MatchReasonLengthDiffers(deltaMinutes: 45)) == "45m longer")
        #expect(MatchCopy.reason(MatchReasonSameChapterCount(count: 36)) == "36 chapters")
        #expect(MatchCopy.reason(MatchReasonDifferentEdition(format: .dramatized)) == "Dramatized edition")
    }

    @Test func aRowsMetadataLineTellsEditionsApart() {
        #expect(MatchCopy.metadataLine(F.candidate()) == "Ray Porter · 16h 10m · 2021 · Unabridged")
        let cast = F.candidate(narrators: ["A", "B", "C"], durationMs: 35_040_000, year: 2023, format: nil)
        #expect(MatchCopy.metadataLine(cast) == "Full cast · 9h 44m · 2023")
    }

    @Test func theStoreButtonNamesItsSourceAndStore() {
        #expect(MatchCopy.storeButton(source: F.storefront, store: F.unitedStates).hasPrefix("Storefront store: "))
    }

    @Test func thePartialBannerSaysWhoDidntAnswer() {
        let banner = MatchCopy.partialBanner(PartialFailure(failed: [F.shelfdata], answered: [F.storefront, F.tuneshop]))
        #expect(banner.message == "Shelfdata didn't answer, so these results are from Storefront and Tuneshop.")
        #expect(banner.retryTitle == "Retry Shelfdata")
    }

    @Test func theRateLimitCountdownIsMinutesAndSeconds() {
        #expect(MatchCopy.countdown(seconds: 30) == "0:30")
        #expect(MatchCopy.countdown(seconds: 95) == "1:35")
        #expect(MatchCopy.countdown(seconds: -4) == "0:00")
    }

    // MARK: - Review

    @Test func valuesReadAsPlainText() {
        #expect(MatchCopy.value(FieldValueYear(year: 2021)) == "2021")
        #expect(MatchCopy.value(FieldValuePeople(names: ["Ray Porter", "Andy Weir"])) == "Ray Porter, Andy Weir")
        #expect(MatchCopy.value(F.text("<p>Ryland Grace is the <b>sole</b> survivor.</p>")) == "Ryland Grace is the sole survivor.")
    }

    @Test func theEditedNoteSaysWhoAndWhen() {
        let gb = Locale(identifier: "en_GB")
        let day = F.twelfthOfSeptember
        let mine = HandEdit(byUserId: "u1", byName: "Simon", at: day)
        #expect(MatchCopy.editedNote(mine, viewerId: "u1", locale: gb) == "Edited by you, 12 Sep. Kept unless you tick it.")
        #expect(MatchCopy.editedNote(mine, viewerId: "u2", locale: gb) == "Edited by Simon, 12 Sep. Kept unless you tick it.")
        let nobody = HandEdit(byUserId: nil, byName: nil, at: day)
        #expect(MatchCopy.editedNote(nobody, viewerId: "u1", locale: gb) == "Edited by hand, 12 Sep. Kept unless you tick it.")
    }

    @Test func theEditedNoteLeavesOutAnUnknownDate() {
        let undated = HandEdit(byUserId: "u1", byName: nil, at: nil)
        #expect(MatchCopy.editedNote(undated, viewerId: "u1") == "Edited by you. Kept unless you tick it.")
        let zero = HandEdit(byUserId: nil, byName: "Sam", at: 0)
        #expect(MatchCopy.editedNote(zero, viewerId: "u1") == "Edited by Sam. Kept unless you tick it.")
        #expect(MatchCopy.editedNote(nil, viewerId: nil) == "Edited by hand. Kept unless you tick it.")
    }

    @Test func alreadyTheSameListsTheFieldsAndLength() {
        #expect(MatchCopy.alreadySame([.authors, .narrators], lengthAlreadySame: true)
            == "3 fields already match: Authors, Narrators, Length")
        #expect(MatchCopy.alreadySame([.series], lengthAlreadySame: false) == "1 field already matches: Series")
        #expect(MatchCopy.alreadySame([], lengthAlreadySame: false) == nil)
    }

    @Test func theApplyBarCountsWhatApplyWrites() {
        #expect(MatchCopy.applyBar(ApplySummary(fieldCount: 5, coverChanges: true, chapterNameCount: 16))
            == "5 fields · cover · 16 chapter names")
        #expect(MatchCopy.applyBar(ApplySummary(fieldCount: 1, coverChanges: false, chapterNameCount: 1))
            == "1 field · 1 chapter name")
        #expect(MatchCopy.applyBar(ApplySummary(fieldCount: 0, coverChanges: false, chapterNameCount: 0))
            == "Nothing selected")
    }

    @Test func anApplyErrorAlwaysSaysNothingWasChanged() {
        #expect(MatchCopy.applyError("The server refused that.") == "The server refused that. Nothing was changed.")
        #expect(MatchCopy.applyError("It failed. Nothing was changed.") == "It failed. Nothing was changed.")
    }

    @Test func whatWillChangeLeavesOutZeroCounts() {
        let summary = WhatWillChange(
            coverSource: F.shelfdata, changeCount: 1, gapCount: 2, labelsAdded: 0, labelsRemoved: 0,
            chapterNameCount: 16, keptEditedCount: 0
        )
        let items = MatchCopy.summaryItems(summary)
        #expect(items.map(\.section) == [.cover, .changes, .fillsGap, .chapterNames])
        #expect(items.map(\.value) == ["Cover", "1", "2", "16"])
        #expect(items.first?.caption == "from Shelfdata")
    }

    // MARK: - Receipt

    @Test func theReceiptSaysWhatChangedAndFromWhere() {
        let receipt = MatchReceiptUi(
            receiptId: "r1", fieldCount: 5, coverSource: F.shelfdata, chapterNameCount: 16, changes: [], undoable: true
        )
        #expect(MatchCopy.receipt(receipt) == "Changed 5 fields, cover from Shelfdata, 16 chapter names")
        let none = MatchReceiptUi(receiptId: "r2", fieldCount: 0, coverSource: nil, chapterNameCount: 0, changes: [], undoable: true)
        #expect(MatchCopy.receipt(none) == "Matched. Nothing needed changing.")
    }

    @Test func seeWhatChangedListsEveryChangeWithItsSource() {
        #expect(MatchCopy.changeLines(AppliedChangeField(field: .publisher, source: F.storefront)) == ["Publisher · from Storefront"])
        #expect(MatchCopy.changeLines(AppliedChangeCover(source: F.shelfdata)) == ["Cover · from Shelfdata"])
        #expect(MatchCopy.changeLines(AppliedChangeGenres(added: ["Thriller"], removed: ["Space Opera"]))
            == ["Genres added: Thriller", "Genres removed: Space Opera"])
        #expect(MatchCopy.changeLines(AppliedChangeChapterNames(count: 16, source: F.storefront))
            == ["16 chapter names · from Storefront"])
    }
}
