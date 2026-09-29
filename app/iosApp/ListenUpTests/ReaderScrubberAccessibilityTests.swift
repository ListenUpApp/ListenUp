import Testing
@testable import ListenUp

/// The reader's page scrubber moves one page per VoiceOver swipe, clamped to the document.
@Suite("Reader scrubber accessibility")
struct ReaderScrubberAccessibilityTests {
    @Test func swipeUpTurnsToTheNextPage() {
        #expect(adjustedPageIndex(from: 3, pageCount: 10, forward: true) == 4)
    }

    @Test func swipeDownTurnsBackAPage() {
        #expect(adjustedPageIndex(from: 3, pageCount: 10, forward: false) == 2)
    }

    @Test func staysOnTheFirstAndLastPage() {
        #expect(adjustedPageIndex(from: 0, pageCount: 10, forward: false) == 0)
        #expect(adjustedPageIndex(from: 9, pageCount: 10, forward: true) == 9)
    }
}
