import Testing
@testable import ListenUp

/// Pins the alphabet sectioning for the Library Series + Contributors tabs. The regression guard:
/// grouping/ordering runs over the native `SeriesRow`/`ContributorRow` value types (cheap Swift
/// strings), not re-bridged Kotlin objects — the same scrubber-hang fix as `BookSectioningTests`.
@Suite("Series + Contributor sectioning")
struct SeriesContributorSectioningTests {
    private func series(_ id: String, _ name: String) -> SeriesRow {
        SeriesRow(id: id, name: name, bookCount: 1, authorName: nil, covers: [])
    }

    private func person(_ id: String, _ name: String) -> ContributorRow {
        ContributorRow(id: id, name: name, bookCount: 1, imagePath: nil)
    }

    // MARK: - Series alphabet index

    @Test func seriesIndexHasFirstIdPerLetterInFirstSeenOrder() {
        let index = seriesAlphabetIndex(
            from: [series("1", "Alpha"), series("2", "Anchor"), series("3", "Beta")],
            ignoreArticles: false
        )
        #expect(index.map(\.letter) == ["A", "B"])
        #expect(index.first?.firstId == "series-1")
        #expect(index.last?.firstId == "series-3")
    }

    @Test func seriesIndexBucketsNonLettersUnderHash() {
        let index = seriesAlphabetIndex(from: [series("1", "1984"), series("2", "Mid")], ignoreArticles: false)
        #expect(index.map(\.letter) == ["#", "M"])
    }

    @Test func seriesIndexEmptyInputYieldsNoEntries() {
        #expect(seriesAlphabetIndex(from: [], ignoreArticles: false).isEmpty)
    }

    @Test func seriesIndexIgnoresLeadingArticlesWhenEnabled() {
        let index = seriesAlphabetIndex(
            from: [series("1", "The Expanse"), series("2", "A Song of Ice"), series("3", "Mistborn")],
            ignoreArticles: true
        )
        // "The Expanse"→E, "A Song of Ice"→S, "Mistborn"→M, kept in first-seen (pre-sorted) order.
        #expect(index.map(\.letter) == ["E", "S", "M"])
        #expect(index.first?.firstId == "series-1")
    }

    // MARK: - Contributor grouping (over native ContributorRow)

    @Test func contributorGroupsByUppercasedFirstLetterPreservingOrder() {
        let groups = ContributorLetterGrouping.group(
            [person("1", "adams"), person("2", "Asimov"), person("3", "Brooks")],
            key: { $0.name }
        )
        #expect(groups.map(\.letter) == ["A", "B"])
        #expect(groups.first?.items.map(\.id) == ["1", "2"])
    }
}
