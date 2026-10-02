import Foundation
import Testing
import Shared
@testable import ListenUp

// Book Detail's Hardcover section: the shared state lands on its native phase, every sync state has
// its own words, and a match made a moment ago reads "Matched just now" in place of the sync.

@Suite("Book Detail Hardcover mapping")
struct BookHardcoverObserverTests {
    private let match = HardcoverMatchedBook(
        hcBookId: 427_578, title: "Project Hail Mary", authors: ["Andy Weir"], releaseYear: 2021,
        chosenByYou: true, hcEditionId: nil, method: nil
    )

    @Test func hiddenAndNeedsMatchMapDirectly() {
        #expect(BookHardcoverObserver.phase(from: BookHardcoverUiStateHidden.shared) == .hidden)
        #expect(BookHardcoverObserver.phase(from: BookHardcoverUiStateNeedsMatch.shared) == .needsMatch)
    }

    @Test func aLinkedBookCarriesItsMatchAndWhereItStands() {
        let state = BookHardcoverUiStateLinked(match: match, sync: .waiting, justMatched: false, keepOffRemoves: nil)
        #expect(BookHardcoverObserver.phase(from: state) == .linked(
            BookHardcoverLinkedModel(
                title: "Project Hail Mary",
                byline: "Andy Weir · 2021",
                chosenByYou: true,
                status: BookHardcoverObserver.status(for: .waiting)
            )
        ))
    }

    @Test func aMatchMadeJustNowSaysSoInPlaceOfTheSync() {
        let state = BookHardcoverUiStateLinked(match: match, sync: .nothingSentYet, justMatched: true, keepOffRemoves: nil)
        guard case .linked(let model) = BookHardcoverObserver.phase(from: state) else {
            Issue.record("expected .linked")
            return
        }
        #expect(model.status.text == String(localized: "hardcover.book_row_just_matched"))
        #expect(model.status.tone == .settled)
    }

    @Test func everySyncStateHasItsOwnWordsAndTone() {
        let cases: [(HardcoverBookSync, String, BookHardcoverStatus.Tone)] = [
            (.upToDate, String(localized: "hardcover.book_sync_up_to_date"), .settled),
            (.waiting, String(localized: "hardcover.book_sync_waiting"), .quiet),
            (.nothingSentYet, String(localized: "hardcover.book_sync_nothing_yet"), .quiet),
            (.removedOnHardcover, String(localized: "hardcover.book_sync_removed"), .caution)
        ]
        for (sync, words, tone) in cases {
            let status = BookHardcoverObserver.status(for: sync)
            #expect(status.text == words)
            #expect(status.tone == tone)
        }
    }
    @Test func aKeptOffBookMapsToKeptOffAndItsToggleReadsOff() {
        let phase = BookHardcoverObserver.phase(from: BookHardcoverUiStateKeptOff(isResuming: false))
        #expect(phase == .keptOff(isResuming: false))
        #expect(phase.isSyncOn == false)
        #expect(BookHardcoverObserver.phase(from: BookHardcoverUiStateKeptOff(isResuming: true)).isSyncOn)
        #expect(BookHardcoverObserver.phase(from: BookHardcoverUiStateNeedsMatch.shared).isSyncOn)
    }

    // Decision 1: every book has the Toggle while Hardcover is connected.
    @Test func aBookNeverMatchedShowsTheToggleAloneAndOn() {
        let phase = BookHardcoverObserver.phase(from: BookHardcoverUiStateUnmatched.shared)
        #expect(phase == .unmatched)
        #expect(phase.isSyncOn)
    }

    @Test func keepingABookOffAsksFirstOnlyWhenSomethingVisibleLeaves() {
        func message(_ removes: KeepOffRemoves?) -> String? {
            let state = BookHardcoverUiStateLinked(match: match, sync: .upToDate, justMatched: false, keepOffRemoves: removes)
            guard case .linked(let model) = BookHardcoverObserver.phase(from: state) else { return "not linked" }
            return model.keepOffMessage
        }
        #expect(message(nil) == nil)
        #expect(message(.reads) == String(localized: "hardcover.keep_off_confirm_body_reads"))
        #expect(message(.toRead) == String(localized: "hardcover.keep_off_confirm_body_to_read"))
        #expect(message(.readsAndToRead) == String(localized: "hardcover.keep_off_confirm_body"))
    }
}
