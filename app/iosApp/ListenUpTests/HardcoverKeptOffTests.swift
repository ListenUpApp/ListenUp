import Foundation
import Testing
import Shared
@testable import ListenUp

// The kept-off list (#1541): the shared state lands on native rows in its own order, Sync Again is announced
// (there is no toast on iOS), the last one closes the list, and a refusal is an alert in the error's own words.

@Suite("Kept off Hardcover mapping")
struct HardcoverKeptOffTests {
    @Test func loadingAndUnavailableMapDirectly() {
        #expect(HardcoverKeptOffObserver.phase(from: KeptOffBooksUiStateLoading.shared) == .loading)
        #expect(HardcoverKeptOffObserver.phase(from: KeptOffBooksUiStateUnavailable.shared) == .unavailable)
    }

    @Test func theBooksArriveAsNativeRowsInTheirOrder() {
        let state = KeptOffBooksUiStateLoaded(books: [
            KeptOffBook(bookId: "b2", title: "Educated", authorNames: "Tara Westover", coverPath: nil, coverHash: nil),
            KeptOffBook(
                bookId: "b1", title: "Mistborn", authorNames: "Brandon Sanderson", coverPath: "p", coverHash: "h"
            )
        ])
        #expect(HardcoverKeptOffObserver.phase(from: state) == .books([
            KeptOffBookRow(id: "b2", title: "Educated", authorNames: "Tara Westover", coverPath: nil, coverHash: nil),
            KeptOffBookRow(
                id: "b1", title: "Mistborn", authorNames: "Brandon Sanderson", coverPath: "p", coverHash: "h"
            )
        ]))
    }

    @Test func syncingAgainIsAnnouncedAndTheLastOneClosesTheList() {
        let words = String(format: String(localized: "hardcover.syncing_again"), "Educated")
        #expect(
            HardcoverKeptOffObserver.effect(of: KeptOffBooksEventSyncingAgain(title: "Educated", wasLast: false))
                == .announce(words, close: false)
        )
        #expect(
            HardcoverKeptOffObserver.effect(of: KeptOffBooksEventSyncingAgain(title: "Educated", wasLast: true))
                == .announce(words, close: true)
        )
    }

    @Test func aRefusalIsAnAlertInTheErrorsOwnWords() {
        let error = ServerConnectErrorInvalidUrl(correlationId: nil, debugInfo: nil, reason: "bad url")
        #expect(HardcoverKeptOffObserver.effect(of: KeptOffBooksEventShowError(error: error)) == .alert(error.message))
    }
}
