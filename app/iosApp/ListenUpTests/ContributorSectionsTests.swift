import Testing
@testable import ListenUp

/// The Authors and Narrators lists slice their rows by the shared letter runs, and the index jumps
/// to the first person a letter covers.
@Suite("Contributor list sections")
struct ContributorSectionsTests {
    private func row(_ id: String, _ name: String) -> ContributorRow {
        ContributorRow(id: id, name: name, bookCount: 1, imagePath: nil)
    }

    @Test func aNameSortSectionsByTheSharedRuns() {
        let rows = [row("1", "Adams"), row("2", "Asimov"), row("3", "Brooks")]
        let sections = LetterRun.sections(
            of: rows,
            runs: [LetterRun(letter: "A", firstIndex: 0, count: 2), LetterRun(letter: "B", firstIndex: 2, count: 1)]
        )
        #expect(sections.map(\.letter) == ["A", "B"])
        #expect(sections[0].items.map(\.id) == ["1", "2"])
    }

    @Test func otherSortsAreOneUnletteredSectionInTheGivenOrder() {
        let sections = LetterRun.sections(of: [row("3", "Brooks"), row("1", "Adams")], runs: [])
        #expect(sections.count == 1)
        #expect(sections[0].letter.isEmpty)
        #expect(sections[0].items.map(\.id) == ["3", "1"])
    }

    @Test func letterJumpsToTheFirstPersonUnderIt() {
        let sections = LetterRun.sections(
            of: [row("a1", "Adams"), row("b1", "Brooks"), row("b2", "Butler")],
            runs: [LetterRun(letter: "A", firstIndex: 0, count: 1), LetterRun(letter: "B", firstIndex: 1, count: 2)]
        )
        #expect(ContributorListContent.scrollTarget(forLetter: "B", in: sections) == "b1")
        #expect(ContributorListContent.scrollTarget(forLetter: "Z", in: sections) == nil)
    }
}
