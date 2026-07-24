import Testing
@testable import ListenUp

@Suite("ContributorLetterGrouping")
struct ContributorLetterGroupingTests {
    @Test func groupsByUppercasedFirstLetterInOrder() {
        let buckets = ContributorLetterGrouping.letterBuckets(["Adams", "Asimov", "Brooks"])
        #expect(buckets.map(\.letter) == ["A", "B"])
        #expect(buckets[0].indices == [0, 1])
        #expect(buckets[1].indices == [2])
    }

    @Test func foldsCaseToTheSameLetter() {
        let buckets = ContributorLetterGrouping.letterBuckets(["adams", "Asimov"])
        #expect(buckets.map(\.letter) == ["A"])
        #expect(buckets[0].indices == [0, 1])
    }

    @Test func nonAlphaGoesUnderHash() {
        let buckets = ContributorLetterGrouping.letterBuckets(["3 Body", "Adams", "中文"])
        #expect(buckets.map(\.letter) == ["#", "A"])
        #expect(buckets.first(where: { $0.letter == "#" })?.indices == [0, 2])
    }

    @Test func emptyInputYieldsNoBuckets() {
        #expect(ContributorLetterGrouping.letterBuckets([]).isEmpty)
    }
}
