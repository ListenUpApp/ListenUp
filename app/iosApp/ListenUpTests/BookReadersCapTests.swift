import Testing
@testable import ListenUp

/// Book Detail shows three readers, then "See All N Readers" (spec §2.10, on every platform).
@Suite("Book Detail readers cap")
struct BookReadersCapTests {
    private func rows(_ count: Int) -> [BookReaderRow] {
        (0..<count).map {
            BookReaderRow(id: "u\($0)", displayName: "R\($0)", initials: "R", isYou: false, progressPercent: 10,
                          lastFinished: nil)
        }
    }

    @Test func atMostThreeRowsAndASeeAllCount() {
        let cap = BookReadersSection.capped(rows(6))
        #expect(cap.shown.count == 3)
        #expect(cap.total == 6)
        #expect(cap.hasMore)
    }

    @Test func threeOrFewerShowNoSeeAll() {
        let cap = BookReadersSection.capped(rows(3))
        #expect(cap.total == 3)
        #expect(cap.hasMore == false)
    }
}
