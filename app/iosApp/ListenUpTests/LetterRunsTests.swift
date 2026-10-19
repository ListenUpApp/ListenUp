import Testing
@testable import ListenUp

/// The shared letter sections, as iOS slices its lists and builds its section index from them.
@Suite("Letter runs")
struct LetterRunsTests {
    private let runs = [
        LetterRun(letter: "A", firstIndex: 0, count: 2),
        LetterRun(letter: "M", firstIndex: 2, count: 1)
    ]

    @Test func sectionsSliceTheListByRun() {
        let sections = LetterRun.sections(of: ["Alpha", "Amber", "Mist"], runs: runs)
        #expect(sections.map(\.letter) == ["A", "M"])
        #expect(sections.map(\.items) == [["Alpha", "Amber"], ["Mist"]])
        #expect(sections.map(\.id) == ["section-0", "section-1"])
    }

    @Test func noRunsIsOneUnlabelledSection() {
        let sections = LetterRun.sections(of: ["x", "y"], runs: [])
        #expect(sections.count == 1)
        #expect(sections[0].letter.isEmpty)
        #expect(sections[0].items == ["x", "y"])
    }

    @Test func noItemsIsNoSections() {
        #expect(LetterRun.sections(of: [String](), runs: runs).isEmpty)
    }

    @Test func aRunPastTheEndOfAShorterListIsClampedRatherThanTrapping() {
        let sections = LetterRun.sections(of: ["Alpha"], runs: runs)
        #expect(sections.map(\.items) == [["Alpha"], []])
    }

    @Test func railLettersKeepListOrderAndJumpToTheFirstRun() {
        let recurring = [
            LetterRun(letter: "#", firstIndex: 0, count: 1),
            LetterRun(letter: "B", firstIndex: 1, count: 1),
            LetterRun(letter: "#", firstIndex: 2, count: 1)
        ]
        #expect(LetterRun.railLetters(recurring) == ["#", "B"])
        #expect(LetterRun.sectionId(forLetter: "#", in: recurring) == "section-0")
        #expect(LetterRun.sectionId(forLetter: "Z", in: recurring) == nil)
    }

    @Test func aDescendingSortRunsTheRailZToA() {
        let descending = [
            LetterRun(letter: "Z", firstIndex: 0, count: 1),
            LetterRun(letter: "A", firstIndex: 1, count: 1)
        ]
        #expect(LetterRun.railLetters(descending) == ["Z", "A"])
    }

    @Test func aRecurringLetterKeepsEverySectionIdDistinct() {
        let recurring = [
            LetterRun(letter: "#", firstIndex: 0, count: 1),
            LetterRun(letter: "#", firstIndex: 1, count: 1)
        ]
        let ids = LetterRun.sections(of: ["1", "2"], runs: recurring).map(\.id)
        #expect(Set(ids).count == 2)
    }
}
