import Testing
@testable import ListenUp

/// How `StatStrip` falls back when its stats don't fit on one line (a large text size, a narrow
/// window): the stats regroup into rows of a fixed width, in order, the last row short.
@Suite("Stat strip layout")
struct StatStripLayoutTests {
    private let stats: [StatStrip.Stat] = ["Listened", "Finished", "Day streak", "Best"]
        .map { StatStrip.Stat(value: "0", label: $0) }

    @Test func fourStatsMakeTwoRowsOfTwo() {
        let rows = StatStrip.rows(of: stats, columns: 2)
        #expect(rows.map { $0.map(\.label) } == [["Listened", "Finished"], ["Day streak", "Best"]])
    }

    @Test func anOddCountLeavesTheLastRowShort() {
        let rows = StatStrip.rows(of: Array(stats.prefix(3)), columns: 2)
        #expect(rows.map(\.count) == [2, 1])
    }

    @Test func oneColumnStacksEveryStat() {
        #expect(StatStrip.rows(of: stats, columns: 1).map(\.count) == [1, 1, 1, 1])
    }
}
