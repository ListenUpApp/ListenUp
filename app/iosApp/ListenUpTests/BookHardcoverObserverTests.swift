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
        chosenByYou: true, hcEditionId: nil
    )

    @Test func hiddenAndNeedsMatchMapDirectly() {
        #expect(BookHardcoverObserver.phase(from: BookHardcoverUiStateHidden.shared) == .hidden)
        #expect(BookHardcoverObserver.phase(from: BookHardcoverUiStateNeedsMatch.shared) == .needsMatch)
    }

    @Test func aLinkedBookCarriesItsMatchAndWhereItStands() {
        let state = BookHardcoverUiStateLinked(match: match, sync: .waiting, justMatched: false)
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
        let state = BookHardcoverUiStateLinked(match: match, sync: .nothingSentYet, justMatched: true)
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
}
