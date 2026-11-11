import Testing
@testable import ListenUp

/// A reader partway through says how far in words ("Listening · 62%"), with the bar beneath drawing it.
@Suite("Book Detail reader status line")
struct BookReadersStatusTextTests {
    private func reader(_ percent: Int?, isLive: Bool = false) -> BookReaderRow {
        BookReaderRow(id: "u1", displayName: "Maya Vance", initials: "MV", isYou: false, progressPercent: percent,
                      lastFinished: nil, isLive: isLive)
    }

    @Test func aReaderPartwayThroughSaysListeningWithTheirPercent() {
        #expect(BookReadersSection.readingStatus(for: reader(62)) == "Listening · 62%")
    }

    @Test func yourLiveRowSaysListeningNowWithItsPercent() {
        #expect(BookReadersSection.readingStatus(for: reader(38, isLive: true)) == "Listening now · 38%")
    }

    @Test func theEndsOfTheBookPrintAsWholeNumbers() {
        #expect(BookReadersSection.readingStatus(for: reader(0)) == "Listening · 0%")
        #expect(BookReadersSection.readingStatus(for: reader(100)) == "Listening · 100%")
    }
}
