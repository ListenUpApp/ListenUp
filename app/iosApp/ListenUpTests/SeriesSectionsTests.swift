import Testing
@testable import ListenUp

/// The Series tab under a name sort carries a lettered header per shared letter run (spec L25), as
/// the Authors and Narrators lists do, and the index jumps to the first series a letter covers.
@Suite("Series sections")
struct SeriesSectionsTests {
    private func row(_ id: String, _ name: String) -> SeriesRow {
        SeriesRow(id: id, name: name, bookCount: 1, authorName: nil, covers: [])
    }

    private let runs = [
        LetterRun(letter: "D", firstIndex: 0, count: 2),
        LetterRun(letter: "W", firstIndex: 2, count: 1)
    ]

    private var lettered: [LetterRun.Section<SeriesRow>] {
        LetterRun.sections(of: [row("d1", "Dune"), row("d2", "Dungeon Crawler Carl"), row("w1", "Wool")], runs: runs)
    }

    @Test func aNameSortIsLetteredBySharedRuns() {
        let sections = lettered
        #expect(SeriesContent.isLettered(sections))
        #expect(sections.map(\.letter) == ["D", "W"])
    }

    @Test func otherSortsAreUnlettered() {
        let sections = LetterRun.sections(of: [row("w1", "Wool"), row("d1", "Dune")], runs: [])
        #expect(!SeriesContent.isLettered(sections))
    }

    @Test func letterJumpsToTheFirstSeriesUnderIt() {
        let sections = lettered
        #expect(SeriesContent.scrollTarget(forLetter: "W", in: sections) == "series-w1")
        #expect(SeriesContent.scrollTarget(forLetter: "Z", in: sections) == nil)
    }
}
