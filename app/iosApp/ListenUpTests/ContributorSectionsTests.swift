import Testing
@testable import ListenUp

@Suite("Contributor list sections")
struct ContributorSectionsTests {
    private func row(_ id: String, _ name: String) -> ContributorRow {
        ContributorRow(id: id, name: name, bookCount: 1, imagePath: nil)
    }

    @Test func nameSortSectionsByLetter() {
        let rows = [row("1", "Adams"), row("2", "Asimov"), row("3", "Brooks")]
        let sections = ContributorLetterGrouping.sections(rows, isNameSort: true)
        #expect(sections.map(\.letter) == ["A", "B"])
        #expect(sections[0].items.map(\.id) == ["1", "2"])
    }

    @Test func otherSortsAreOneUnletteredSectionInTheGivenOrder() {
        let rows = [row("3", "Brooks"), row("1", "Adams")]
        let sections = ContributorLetterGrouping.sections(rows, isNameSort: false)
        #expect(sections.count == 1)
        #expect(sections[0].letter.isEmpty)
        #expect(sections[0].items.map(\.id) == ["3", "1"])
    }

    @Test func noRowsMeansNoSections() {
        #expect(ContributorLetterGrouping.sections([], isNameSort: true).isEmpty)
        #expect(ContributorLetterGrouping.sections([], isNameSort: false).isEmpty)
    }

    @Test func cacheRegroupsOnlyWhenRowsOrSortChange() {
        var cache = ContributorSectionCache()
        let rows = [row("1", "Adams"), row("2", "Brooks")]
        let changed1 = cache.update(rows: rows, isNameSort: true)
        #expect(changed1)
        #expect(cache.sections.map(\.letter) == ["A", "B"])

        // The same state re-emitted (a position save, a sync tick) is not a regroup.
        let changed2 = cache.update(rows: rows, isNameSort: true)
        #expect(!changed2)

        let changed3 = cache.update(rows: rows, isNameSort: false)
        #expect(changed3)
        #expect(cache.sections.map(\.letter) == [""])

        let changed4 = cache.update(rows: rows + [row("3", "Clarke")], isNameSort: false)
        #expect(changed4)
        #expect(cache.sections[0].items.count == 3)
    }

    @Test func emptyCacheStaysQuietForEmptyRows() {
        var cache = ContributorSectionCache()
        let changed5 = cache.update(rows: [], isNameSort: false)
        #expect(!changed5)
        #expect(cache.sections.isEmpty)
    }
}

@Suite("Contributor scrubber target")
struct ContributorScrubberTargetTests {
    @Test func letterJumpsToTheFirstPersonUnderIt() {
        let sections = ContributorLetterGrouping.sections(
            [
                ContributorRow(id: "a1", name: "Adams", bookCount: 1, imagePath: nil),
                ContributorRow(id: "b1", name: "Brooks", bookCount: 1, imagePath: nil),
                ContributorRow(id: "b2", name: "Butler", bookCount: 1, imagePath: nil)
            ],
            isNameSort: true
        )
        #expect(ContributorListContent.scrollTarget(forLetter: "B", in: sections) == "b1")
        #expect(ContributorListContent.scrollTarget(forLetter: "Z", in: sections) == nil)
    }
}
