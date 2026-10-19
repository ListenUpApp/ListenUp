import Foundation
import Testing
@testable import ListenUp

/// Discover's sentences that take numbers or dates read the same as every other platform's.
@Suite("Discover copy")
struct DiscoverCopyTests {
    @Test func countLineCoversEveryMix() {
        #expect(DiscoverCopy.listeningCount(live: 2, recent: 3) == "2 listening now · 3 more in the last two weeks")
        #expect(DiscoverCopy.listeningCount(live: 2, recent: 0) == "2 listening now")
        #expect(DiscoverCopy.listeningCount(live: 0, recent: 3) == "3 in the last two weeks")
        #expect(DiscoverCopy.listeningCount(live: 0, recent: 0) == nil)
    }

    @Test func seriesLineIsNumberedBareOrStandalone() {
        #expect(DiscoverCopy.seriesLine(series: "Mistborn", sequence: "1") == "Book 1 of Mistborn")
        #expect(DiscoverCopy.seriesLine(series: "Mistborn", sequence: nil) == "Mistborn")
        #expect(DiscoverCopy.seriesLine(series: nil, sequence: nil) == "Standalone")
    }

    /// The platform formats the length; read what it says rather than pinning one OS's punctuation.
    @Test func lengthReadsHoursAndMinutesTheSystemsWay() {
        let expected = Duration.seconds(24 * 3_600 + 39 * 60)
            .formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
        #expect(DiscoverCopy.length(ms: 88_740_000) == expected)
        #expect(expected.contains("24"))
        #expect(expected.contains("39"))
    }

    @Test func asOfSaysHowOldTheRosterIs() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let twoHoursAgo = now.addingTimeInterval(-2 * 3_600)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        formatter.dateTimeStyle = .named
        let words = formatter.localizedString(for: twoHoursAgo, relativeTo: now)
        #expect(DiscoverCopy.asOf(twoHoursAgo, relativeTo: now) == "As of \(words)")
    }

    @Test func addedReadsInWords() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let twoDaysAgo = now.addingTimeInterval(-2 * 86_400)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        formatter.dateTimeStyle = .named
        let words = formatter.localizedString(for: twoDaysAgo, relativeTo: now)
        #expect(DiscoverCopy.added(twoDaysAgo, relativeTo: now) == "Added \(words)")
        #expect(words == "2 days ago")
    }

    @Test func listenerLabelNamesTheBookThenThePerson() {
        #expect(
            DiscoverCopy.listenerLabel(book: "Mistborn", name: "Maya Chen", isLive: true, when: "")
                == "Mistborn, Maya Chen is listening now"
        )
        #expect(
            DiscoverCopy.listenerLabel(book: "Mistborn", name: "Maya Chen", isLive: false, when: "2 hr. ago")
                == "Mistborn, Maya Chen listened 2 hr. ago"
        )
    }
}
