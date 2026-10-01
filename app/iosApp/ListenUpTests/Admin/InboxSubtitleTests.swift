import Testing
@testable import ListenUp

/// The line under the inbox's title: what is selected, or what waits — formatted, and never a release
/// that has not happened.
@Suite("Inbox subtitle")
struct InboxSubtitleTests {
    @Test func oneSelectedIsCountedNotAFormatString() {
        #expect(ReleaseToEveryone.subtitle(bookCount: 4, selectedCount: 1) == "1 selected")
    }

    @Test func severalSelectedAreSelectedNotReleased() {
        #expect(ReleaseToEveryone.subtitle(bookCount: 4, selectedCount: 3) == "3 selected")
    }

    @Test func withNothingSelectedItSaysWhatWaits() {
        #expect(ReleaseToEveryone.subtitle(bookCount: 1, selectedCount: 0) == "1 book awaiting review")
        #expect(ReleaseToEveryone.subtitle(bookCount: 4, selectedCount: 0) == "4 books awaiting review")
    }
}
