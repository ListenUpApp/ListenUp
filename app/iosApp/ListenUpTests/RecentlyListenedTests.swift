import Testing
import Shared
@testable import ListenUp

/// The profile's "Recently listened" strip, on both `UserProfileView` (your own) and
/// `ForeignProfileView` (someone else's).
///
/// The shared `UserProfileUiState.Ready` isn't constructible from Swift, so `UserProfileObserver.apply`
/// itself lands at the green-build pass. What it does with the books is a pure projection —
/// `ProfileRecentBookRow.rows(from:)` — and that, plus the strip's own decisions (whether it shows,
/// what it is called, where a cover goes), is pinned here against the real exported `ProfileRecentBook`.
@Suite("Recently listened")
struct RecentlyListenedTests {

    @Test func observerMapsRecentBooksToNativeRowsInOrder() {
        let books = [
            ProfileRecentBook(bookId: "b1", title: "The Way of Kings", coverHash: "h1"),
            ProfileRecentBook(bookId: "b2", title: "Elantris", coverHash: nil)
        ]

        let rows = ProfileRecentBookRow.rows(from: books)

        #expect(rows == [
            ProfileRecentBookRow(id: "b1", title: "The Way of Kings", coverHash: "h1"),
            ProfileRecentBookRow(id: "b2", title: "Elantris", coverHash: nil)
        ])
    }

    @Test func tappingACoverOpensThatBooksDetail() {
        let row = ProfileRecentBookRow(id: "b9", title: "Elantris", coverHash: nil)

        #expect(row.destination == BookDestination(id: "b9"))
    }

    @Test func anEmptyListRendersNothing() {
        #expect(RecentlyListenedStrip.isShown(books: []) == false)
        #expect(RecentlyListenedStrip.isShown(books: [
            ProfileRecentBookRow(id: "b1", title: "The Way of Kings", coverHash: nil)
        ]))
    }

    /// The same strip sits on both profile screens; only its heading changes with whose it is.
    @Test func headingNamesSomeoneElsesListeningAndAddressesYourOwn() {
        #expect(RecentlyListenedStrip.title(isOwnProfile: false) == "Recently listened")
        #expect(RecentlyListenedStrip.title(isOwnProfile: true) == "What you've been listening to")
    }

    /// VoiceOver reads each cover as its book, not as an unlabeled image.
    @Test func eachCoverIsLabelledWithItsTitle() {
        let row = ProfileRecentBookRow(id: "b1", title: "The Way of Kings", coverHash: nil)

        #expect(row.accessibilityLabel == "The Way of Kings")
    }
}
