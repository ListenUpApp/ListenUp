import Testing
@testable import ListenUp

/// Pure-seam coverage for the book edit screen.
///
/// The sealed-state → observer flatten can't be exercised here: SKIE bridges
/// `BookEditUiState` as a value type whose nested `Editable*` lists aren't
/// ergonomically constructible from Swift, so the `apply(_:)` mapping lands at the
/// green-build pass (the app target proves it compiles). What *is* pure and
/// constructible is the series-chip label formatting, so that seam is pinned here.
@Suite("BookEditFormatting")
struct BookEditObserverTests {
    @Test func seriesLabelWithSequenceJoinsWithMiddot() {
        #expect(BookEditFormatting.seriesLabel(name: "Screwtape", sequence: "1") == "Screwtape · 1")
    }

    @Test func seriesLabelWithoutSequenceIsNameOnly() {
        #expect(BookEditFormatting.seriesLabel(name: "Screwtape", sequence: nil) == "Screwtape")
    }

    @Test func seriesLabelTreatsBlankSequenceAsAbsent() {
        #expect(BookEditFormatting.seriesLabel(name: "Screwtape", sequence: "   ") == "Screwtape")
    }

    @Test func seriesLabelKeepsDecimalSequence() {
        #expect(BookEditFormatting.seriesLabel(name: "Mistborn", sequence: "3.5") == "Mistborn · 3.5")
    }

    @Test func tagLabelTitleCasesHyphenatedSlug() {
        #expect(BookEditFormatting.tagLabel(slug: "found-family") == "Found Family")
    }

    @Test func tagLabelHandlesSingleWord() {
        #expect(BookEditFormatting.tagLabel(slug: "christian") == "Christian")
    }

    @Test func tagLabelDropsEmptySegments() {
        #expect(BookEditFormatting.tagLabel(slug: "slow--burn") == "Slow Burn")
    }
}
