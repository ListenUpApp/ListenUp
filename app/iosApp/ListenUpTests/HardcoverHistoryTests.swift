import Foundation
import Testing
import Shared
@testable import ListenUp

// The earlier-books offer (#1540): each history state mapped once at the observer boundary into a native
// value, and every sentence the section shows, singular and plural.

@Suite("Hardcover earlier books")
struct HardcoverHistoryTests {
    private func connected(history: HardcoverHistory) -> HardcoverSettingsUiStateConnected {
        HardcoverSettingsUiStateConnected(
            username: "simon",
            since: 1_790_424_000_000,
            isDisconnecting: false,
            lastSyncedAt: nil,
            sync: HardcoverSyncStatusIdle.shared,
            booksToMatch: [],
            isMatchListKnown: true,
            shareMode: .asIListen,
            isSavingShareMode: false,
            history: history,
            keptOffBookCount: 0
        )
    }

    private func model(_ history: HardcoverHistory) -> HardcoverHistoryModel? {
        guard case .connected(let model) = HardcoverSettingsObserver.phase(from: connected(history: history)) else {
            Issue.record("expected .connected")
            return nil
        }
        return model.history
    }

    @Test func eachHistoryStateMapsToItsNativeCase() {
        #expect(model(HardcoverHistoryNone.shared) == HardcoverHistoryModel.none)
        #expect(model(HardcoverHistoryOffer(bookCount: 74)) == HardcoverHistoryModel.offer(books: 74))
        #expect(model(HardcoverHistoryAvailable(bookCount: 74)) == HardcoverHistoryModel.available(books: 74))
        #expect(
            model(HardcoverHistorySending(sentBooks: 23, totalBooks: 74))
                == HardcoverHistoryModel.sending(sent: 23, total: 74)
        )
        #expect(
            model(HardcoverHistoryDone(sentBooks: 70, needsMatchBooks: 4))
                == HardcoverHistoryModel.done(sent: 70, needsMatch: 4)
        )
    }

    @Test func theOfferCountsBooksAndSaysOneInTheSingular() {
        #expect(
            HardcoverHistoryText.offerBody(books: 74)
                == "You finished 74 books in ListenUp before connecting. "
                + "Send them to Hardcover as read, with when you started and finished."
        )
        #expect(
            HardcoverHistoryText.offerBody(books: 1)
                == "You finished 1 book in ListenUp before connecting. "
                + "Send it to Hardcover as read, with when you started and finished."
        )
    }

    @Test func theButtonsAreInTitleCase() {
        #expect(HardcoverHistoryText.sendTitle(books: 74) == "Send 74 Books")
        #expect(HardcoverHistoryText.sendTitle(books: 1) == "Send 1 Book")
        #expect(HardcoverHistoryText.notNowTitle() == "Not Now")
        #expect(HardcoverHistoryText.rowTitle() == "Send Earlier Books")
    }

    @Test func sendingSaysHowFarAndIsReadAsAFraction() {
        #expect(HardcoverHistoryText.sendingTitle(sent: 23, total: 74) == "Sending 23 of 74 books…")
        #expect(HardcoverHistoryText.sendingTitle(sent: 0, total: 1) == "Sending 1 book…")
        #expect(HardcoverHistoryText.progressValue(sent: 23, total: 74) == "23 of 74")
    }

    @Test func doneSaysWhatWasSentAndWhatWaitsForAMatch() {
        #expect(HardcoverHistoryText.doneTitle(sent: 70, needsMatch: 4) == "Sent 70 books to Hardcover")
        #expect(HardcoverHistoryText.doneTitle(sent: 74, needsMatch: 0) == "Sent all 74 books to Hardcover")
        #expect(HardcoverHistoryText.doneTitle(sent: 1, needsMatch: 0) == "Sent 1 book to Hardcover")
        #expect(HardcoverHistoryText.needsMatchLine(4) == "4 need a match")
        #expect(HardcoverHistoryText.needsMatchLine(1) == "1 needs a match")
    }

    /// D4: a send that reached nothing never reads "Sent 0 books" — it says what waits for a match.
    @Test func doneWithNothingSentSaysWhatNeedsAMatchInstead() {
        #expect(
            HardcoverHistoryText.doneTitle(sent: 0, needsMatch: 4) == "4 books need a match before they can be sent"
        )
        #expect(
            HardcoverHistoryText.doneTitle(sent: 0, needsMatch: 1) == "1 book needs a match before it can be sent"
        )
    }

    @Test func theQuietRowCountsWhatWasFinishedBeforeConnecting() {
        #expect(HardcoverHistoryText.availableDetail(books: 74) == "74 finished before you connected")
        #expect(HardcoverHistoryText.availableDetail(books: 1) == "1 finished before you connected")
    }
}
