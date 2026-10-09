import Foundation
import Testing
@testable import ListenUp
@preconcurrency import Shared

/// Book Detail's last-match row on iOS: the sentence (when, and by whom when it wasn't you), the receipt that See
/// What Changed opens, and Undo's outcome in the receipt capsule's words.
@Suite("Last match on Book Detail")
struct LastMatchTests {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func ui(appliedAt: Date, matchedBy: String? = nil, showingChanges: Bool = false, undoing: Bool = false) -> LastMatchUi {
        LastMatchUi(
            receipt: MatchReceiptUi(
                receiptId: "r1", fieldCount: 1, coverSource: Fixture.shelfdata, chapterNameCount: 0,
                photoSource: nil, biographySource: nil,
                changes: [
                    AppliedChangeField(field: .publisher, source: Fixture.storefront),
                    AppliedChangeCover(source: Fixture.shelfdata)
                ],
                undoable: true
            ),
            appliedAtMs: Int64(appliedAt.timeIntervalSince1970 * 1000),
            matchedBy: matchedBy,
            showingChanges: showingChanges,
            undoing: undoing,
            undoError: nil
        )
    }

    @Test func aMatchYouJustMadeReadsJustNow() {
        #expect(MatchCopy.lastMatch(appliedAt: now, matchedBy: nil, now: now) == "Details matched just now")
    }

    @Test func anOlderMatchSaysHowLongAgo() {
        let sentence = MatchCopy.lastMatch(appliedAt: now.addingTimeInterval(-2 * 86_400), matchedBy: nil, now: now)
        #expect(sentence == "Details matched 2 days ago")
    }

    @Test func someoneElsesMatchNamesThem() {
        #expect(MatchCopy.lastMatch(appliedAt: now, matchedBy: "Sam", now: now) == "Details matched just now by Sam")
        let older = MatchCopy.lastMatch(appliedAt: now.addingTimeInterval(-3 * 3_600), matchedBy: "Sam", now: now)
        #expect(older == "Details matched 3 hours ago by Sam")
    }

    @Test func theRowCarriesTheReceiptSeeWhatChangedOpens() {
        let row = LastMatchMapping.row(from: ui(appliedAt: now, matchedBy: "Sam", showingChanges: true))
        #expect(row.receiptId == "r1")
        #expect(row.matchedBy == "Sam")
        #expect(row.appliedAt == now)
        #expect(row.showingChanges)
        #expect(row.receipt.sentence == "Changed 1 field, cover from Shelfdata")
        #expect(row.receipt.changes == ["Publisher · from Storefront", "Cover · from Shelfdata"])
        #expect(row.undoError == nil)
    }

    @Test func undoingIsCarriedSoTheRowCanSayIt() {
        #expect(LastMatchMapping.row(from: ui(appliedAt: now, undoing: true)).undoing)
    }

    @Test func undoOutcomesUseTheReceiptsWords() {
        #expect(LastMatchMapping.outcome(from: LastMatchEventUndone.shared) == .undone)
        #expect(LastMatchMapping.outcome(from: LastMatchEventExpired.shared)
            == .expired(message: "This book has changed since, so the match can't be undone."))
    }
}
